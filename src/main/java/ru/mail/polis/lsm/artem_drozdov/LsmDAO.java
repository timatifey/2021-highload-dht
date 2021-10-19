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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class LsmDAO implements DAO {

    private static final Logger LOG = LoggerFactory.getLogger(LsmDAO.class);

    private final AwaitingExecutorService flushService = new AwaitingExecutorService();

    private final AtomicReference<Storage> storageRef;

    private final AtomicBoolean hasClosed = new AtomicBoolean(false);
    private final DAOConfig config;

    public LsmDAO(DAOConfig config) throws IOException {
        this.config = config;
        List<SSTable> ssTables = SSTable.loadFromDir(config.dir);
        this.storageRef = new AtomicReference<>(new Storage(ssTables));
    }

    @Override
    public Iterator<Record> range(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        Storage currStorage = this.storageRef.get();

        Iterator<Record> sstableRanges = sstableRanges(currStorage, fromKey, toKey);

        Iterator<Record> memoryRange = mergeTwo(
                currStorage.memoryStorage.range(fromKey, toKey),
                currStorage.storagesToFlush.range(fromKey, toKey)
        );

        Iterator<Record> iterator = mergeTwo(sstableRanges, memoryRange);
        return new FilterTombstonesIterator(iterator);
    }

    @Override
    public void upsert(Record record) {
        checkCloseState();
        Storage storage = storageRef.get();
        long consumption = storage.memoryStorage.putAndGetSize(record);
        if (consumption > config.memoryLimit) {
            synchronized (this) {
                checkCloseState();
                if (memoryConsumption.get() > config.memoryLimit) {
                    executeFlush(sizeOf(record));
                } else {
                    LOG.info("Concurrent flush");
                }
            }
        }

        checkCloseState();
        storageRef.memoryStorage.put(record);
    }

    private void checkCloseState() {
        if (hasClosed.get()) {
            throw new UncheckedIOException(new IOException("DAO closed"));
        }
    }

    @Override
    public void compact() {
        synchronized (this) {
            LOG.info("Await flush task completing");
            flushService.awaitTaskComplete();
            performCompact();
        }
    }

    private boolean needCompact() {
        return storageRef.tables.size() > config.maxTables;
    }

    @Override
    public void close() throws IOException {
        hasClosed.set(true);

        flushService.awaitTaskComplete();
        flushService.shutdown();

        storageRef = storageRef.prepareBeforeFlush();
        flush(storageRef);
        storageRef = null;
    }

    @GuardedBy("this")
    private void executeFlush(int newMemoryConsumption) {
        LOG.info("Await flush task completing");
        flushService.awaitTaskComplete();
        memoryConsumption.getAndSet(newMemoryConsumption);
        storageRef = storageRef.prepareBeforeFlush();
        flushService.execute(() -> {
            try {
                LOG.info("Flush started");
                SSTable flushedTable = flush(storageRef);
                storageRef = storageRef.afterFlush(flushedTable);
                LOG.info("Flush ended");
            } catch (IOException e) {
                LOG.error("Fail to flush", e);
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
            compactedSSTable = SSTable.compact(config.dir, sstableRanges(storageRef, null, null));
            storageRef = storageRef.afterCompaction(compactedSSTable);
            LOG.info("Compact ended");
        } catch (IOException e) {
            LOG.error("Can't run compaction", e);
        }
    }

    private SSTable flush(Storage storage) throws IOException {
        Path dir = config.dir;
        Path file = dir.resolve(SSTable.SSTABLE_FILE_PREFIX + storage.tables.size());
        Iterator<Record> records = storage.storagesToFlush.values().iterator();
        return SSTable.write(records, file);
    }

    private static int sizeOf(Record record) {
        int keySize = Integer.BYTES + record.getKeySize();
        int valueSize = Integer.BYTES + record.getValueSize();
        return keySize + valueSize;
    }
}
