package ru.mail.polis.lsm.artem_drozdov;

import ru.mail.polis.lsm.Record;
import ru.mail.polis.lsm.artem_drozdov.iterator.MergeTwoIterator;
import ru.mail.polis.lsm.artem_drozdov.iterator.PeekingIterator;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.*;

public final class Storage {

    private static final MemTable EMPTY_STORAGE = new MemTable() {
        @Override
        public long putAndGetSize(Record record) {
            throw new UnsupportedOperationException();
        }
    };

    public final List<MemTable> storagesToFlush;
    public final MemTable memoryStorage;

    public final List<SSTable> tables;

    private Storage(MemTable memoryStorage,
                    List<MemTable> storagesToFlush,
                    List<SSTable> tables) {
        this.memoryStorage = memoryStorage;
        this.storagesToFlush = storagesToFlush;
        this.tables = tables;
    }

    public Storage(List<SSTable> tables) {
        this(newStorage(), Collections.emptyList(), tables);
    }

    public Storage prepareBeforeFlush() {
        List<MemTable> newStoragesToFlush = new ArrayList<>(storagesToFlush.size() + 1);
        newStoragesToFlush.addAll(storagesToFlush);
        newStoragesToFlush.add(memoryStorage);
        return new Storage(newStorage(), newStoragesToFlush, tables);
    }

    public Iterator<Record> iterator(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        Iterator<Record> sstableRanges = sstableRanges(this, fromKey, toKey);

        Iterator<Record> memoryRange = mergeTwo(
                currStorage.memoryStorage.range(fromKey, toKey),
                currStorage.storagesToFlush.range(fromKey, toKey)
        );
    }

    public Storage afterFlush(SSTable newTable) {
        List<SSTable> newTables = new ArrayList<>(tables.size() + 1);
        newTables.addAll(tables);
        newTables.add(newTable);
        return new Storage(memoryStorage, EMPTY_STORAGE, newTables);
    }

    public Storage afterCompaction(SSTable ssTable) {
        List<SSTable> newTables = Collections.singletonList(ssTable);
        return new Storage(memoryStorage, EMPTY_STORAGE, newTables);
    }

    private static MemTable newStorage() {
        return new MemTable();
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
