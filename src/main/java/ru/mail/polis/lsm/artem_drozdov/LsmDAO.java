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
import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LsmDAO implements DAO {

    private static final Logger LOG = LoggerFactory.getLogger(LsmDAO.class);

    private final AwaitingExecutorService flushService = new AwaitingExecutorService();
//    private final AwaitingExecutorService compactService = new AwaitingExecutorService();

    private volatile Storage storage;

    private final AtomicBoolean hasClosed = new AtomicBoolean(false);
    private final AtomicInteger memoryConsumption = new AtomicInteger();
    private final DAOConfig config;

    public LsmDAO(DAOConfig config) throws IOException {
        this.config = config;
        List<SSTable> ssTables = SSTable.loadFromDir(config.dir);
        this.storage = new Storage(ssTables);
    }

    @Override
    public Iterator<Record> range(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        Storage storage = this.storage;

        Iterator<Record> sstableRanges = sstableRanges(storage, fromKey, toKey);

        Iterator<Record> memoryIterator = map(storage.memoryStorage, fromKey, toKey).values().iterator();
        Iterator<Record> flushMemoryIterator = map(storage.storageToFlush, fromKey, toKey).values().iterator();
        Iterator<Record> memoryRange = mergeTwo(memoryIterator, flushMemoryIterator);

        Iterator<Record> iterator = mergeTwo(sstableRanges, memoryRange);
        return new FilterTombstonesIterator(iterator);
    }

    @Override
    public void upsert(Record record) {
        if (hasClosed.get()) {
            throw new UncheckedIOException(new IOException("DAO closed"));
        }

        if (memoryConsumption.addAndGet(sizeOf(record)) > config.memoryLimit) {
            synchronized (this) {
                if (memoryConsumption.get() > config.memoryLimit) {
                    executeFlush(sizeOf(record));
                } else {
                    LOG.info("Concurrent flush");
                }
            }
        }
        storage.memoryStorage.put(record.getKey(), record);
    }

    @Override
    public void compact() {
        synchronized (this) {
            LOG.info("Await flush task completing");
            flushService.awaitTaskComplete();
//            compactService.awaitTaskComplete();
//            compactService.execute(this::performCompact);
            performCompact();
        }
    }

    private boolean needCompact() {
        return storage.tables.size() > config.maxTables;
    }

    @Override
    public void close() throws IOException {
        synchronized (hasClosed) {
            hasClosed.set(true);

            flushService.awaitTaskComplete();
            flushService.shutdown();

//            compactService.awaitTaskComplete();
//            compactService.shutdown();

            storage = storage.prepareBeforeFlush();
            flush(storage);
            storage = null;
        }
    }

    @GuardedBy("this")
    private void executeFlush(int newMemoryConsumption) {
        LOG.info("Await flush task completing");
        flushService.awaitTaskComplete();
        memoryConsumption.getAndSet(newMemoryConsumption);
        storage = storage.prepareBeforeFlush();
        flushService.execute(() -> {
            synchronized (LsmDAO.this) {
                try {
                    LOG.info("Flush started");
                    SSTable flushedTable = flush(storage);
                    storage = storage.afterFlush(flushedTable);
                    LOG.info("Flush ended");
//                    if (needCompact()) {
//                        compactService.awaitTaskComplete();
//                        compactService.execute(this::performCompact);
//                    }
                } catch (IOException e) {
                    LOG.error("Fail to flush", e);
                }
            }
        });
    }

    @GuardedBy("this")
    private void performCompact() {
        try {
            if (!needCompact()) {
                return;
            }
            LOG.info("Compact started");
            SSTable compactedSSTable;
            try {
                compactedSSTable = SSTable.compact(config.dir, sstableRanges(storage, null, null));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            storage = storage.afterCompaction(compactedSSTable);
            LOG.info("Compact ended");
        } catch (Exception e) {
            LOG.error("Can't run compaction", e);
        }
    }

    private SSTable flush(Storage storage) throws IOException {
        Path dir = config.dir;
        Path file = dir.resolve(SSTable.SSTABLE_FILE_PREFIX + storage.tables.size());
        Iterator<Record> records = storage.storageToFlush.values().iterator();
        return SSTable.write(records, file);
    }

    private static Iterator<Record> sstableRanges(Storage storage,
                                                  @Nullable ByteBuffer fromKey,
                                                  @Nullable ByteBuffer toKey) {
        List<Iterator<Record>> iterators = new ArrayList<>(storage.tables.size());
        for (SSTable ssTable : storage.tables) {
            iterators.add(ssTable.range(fromKey, toKey));
        }
        return merge(iterators);
    }

    private static SortedMap<ByteBuffer, Record> map(NavigableMap<ByteBuffer, Record> storage,
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

    private static int sizeOf(Record record) {
        int keySize = Integer.BYTES + record.getKeySize();
        int valueSize = Integer.BYTES + record.getValueSize();
        return keySize + valueSize;
    }
}
