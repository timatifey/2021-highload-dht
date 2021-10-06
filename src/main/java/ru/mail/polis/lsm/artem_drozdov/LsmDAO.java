package ru.mail.polis.lsm.artem_drozdov;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.lsm.DAO;
import ru.mail.polis.lsm.DAOConfig;
import ru.mail.polis.lsm.Record;
import ru.mail.polis.lsm.artem_drozdov.iterator.FilterTombstonesIterator;
import ru.mail.polis.lsm.artem_drozdov.iterator.MergeTwoIterator;
import ru.mail.polis.lsm.artem_drozdov.iterator.PeekingIterator;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LsmDAO implements DAO {

    private static final Logger LOG = LoggerFactory.getLogger(LsmDAO.class);

    private final ExecutorService flushService = Executors.newSingleThreadExecutor();

    private Future<?> flushFuture;

    private NavigableMap<ByteBuffer, Record> flushMemoryStorage = newStorage();
    private NavigableMap<ByteBuffer, Record> memoryStorage = newStorage();
    private final ConcurrentLinkedDeque<SSTable> tables = new ConcurrentLinkedDeque<>();

    private final DAOConfig config;

    private final AtomicInteger memoryConsumption = new AtomicInteger();

    public LsmDAO(DAOConfig config) throws IOException {
        this.config = config;
        List<SSTable> ssTables = SSTable.loadFromDir(config.dir);
        tables.addAll(ssTables);
    }

    @Override
    public Iterator<Record> range(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        Iterator<Record> sstableRanges = sstableRanges(fromKey, toKey);

        Iterator<Record> memoryIterator = map(memoryStorage, fromKey, toKey).values().iterator();
        Iterator<Record> flushMemoryIterator = map(flushMemoryStorage, fromKey, toKey).values().iterator();
        Iterator<Record> memoryRange = mergeTwo(memoryIterator, flushMemoryIterator);

        Iterator<Record> iterator = mergeTwo(sstableRanges, memoryRange);
        return new FilterTombstonesIterator(iterator);
    }

    @Override
    public void upsert(Record record) {
        if (memoryConsumption.addAndGet(sizeOf(record)) > config.memoryLimit) {
            synchronized (this) {
                if (memoryConsumption.get() > config.memoryLimit) {
                    submitFlush(sizeOf(record));
                }
            }
        }
        memoryStorage.put(record.getKey(), record);
    }

    @Override
    public void closeAndCompact() throws IOException {
        waitUntilFlushFuturesEnd();
        SSTable table = SSTable.compact(config.dir, range(null, null));
        tables.clear();
        tables.add(table);
        memoryStorage = newStorage();
    }

    private NavigableMap<ByteBuffer, Record> newStorage() {
        return new ConcurrentSkipListMap<>();
    }

    private NavigableMap<ByteBuffer, Record> newStorage(NavigableMap<ByteBuffer, Record> from) {
        return new ConcurrentSkipListMap<>(from);
    }

    private int sizeOf(Record record) {
        int keySize = Integer.BYTES + record.getKeySize();
        int valueSize = Integer.BYTES + record.getValueSize();
        return keySize + valueSize;
    }

    @Override
    public void close() throws IOException {
        waitUntilFlushFuturesEnd();
        flush(memoryStorage);
        shutdownAndAwaitTermination();
    }

    private void submitFlush(int newMemoryConsumption) {
        waitUntilFlushFuturesEnd();

        int memoryConsumptionBeforeFlush = memoryConsumption.getAndSet(newMemoryConsumption);
        flushMemoryStorage = newStorage(memoryStorage);
        memoryStorage = newStorage();
        flushFuture = flushService.submit(() -> {
            try {
                flush(flushMemoryStorage);
            } catch (IOException e) {
                memoryConsumption.addAndGet(memoryConsumptionBeforeFlush);
                memoryStorage.putAll(flushMemoryStorage);
            } finally {
                flushMemoryStorage.clear();
            }
        });
    }

    private void waitUntilFlushFuturesEnd() {
        if (flushFuture != null) {
            try {
                flushFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.error("waitUntilFlushFutureEnd: {}", e.getMessage());
            }
        }
    }

    private void shutdownAndAwaitTermination() {
        flushService.shutdown();
        try {
            if (!flushService.awaitTermination(60, TimeUnit.SECONDS)) {
                flushService.shutdownNow();
                if (!flushService.awaitTermination(60, TimeUnit.SECONDS)) {
                    LOG.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException e) {
            flushService.shutdownNow();
            LOG.error(e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    private void flush(NavigableMap<ByteBuffer, Record> storage) throws IOException {
        LOG.info("Flush");
        Path dir = config.dir;
        Path file = dir.resolve(SSTable.SSTABLE_FILE_PREFIX + tables.size());

        Iterator<Record> records = storage.values().iterator();
        SSTable ssTable = SSTable.write(records, file);
        tables.add(ssTable);
    }

    private Iterator<Record> sstableRanges(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        List<Iterator<Record>> iterators = new ArrayList<>(tables.size());
        for (SSTable ssTable : tables) {
            iterators.add(ssTable.range(fromKey, toKey));
        }
        return merge(iterators);
    }

    private SortedMap<ByteBuffer, Record> map(NavigableMap<ByteBuffer, Record> storage,
                                              @Nullable ByteBuffer fromKey,
                                              @Nullable ByteBuffer toKey) {
        if (fromKey == null && toKey == null) {
            return storage;
        }
        if (fromKey == null) {
            return storage.headMap(toKey);
        }
        if (toKey == null) {
            return storage.tailMap(fromKey);
        }
        return storage.subMap(fromKey, toKey);
    }

    private static Iterator<Record> merge(List<Iterator<Record>> iterators) {
        if (iterators.isEmpty()) {
            return Collections.emptyIterator();
        }
        if (iterators.size() == 1) {
            return iterators.get(0);
        }
        if (iterators.size() == 2) {
            return mergeTwo(iterators.get(0), iterators.get(1));
        }
        Iterator<Record> left = merge(iterators.subList(0, iterators.size() / 2));
        Iterator<Record> right = merge(iterators.subList(iterators.size() / 2, iterators.size()));
        return mergeTwo(left, right);
    }

    private static Iterator<Record> mergeTwo(Iterator<Record> left, Iterator<Record> right) {
        return new MergeTwoIterator(new PeekingIterator(left), new PeekingIterator(right));
    }
}
