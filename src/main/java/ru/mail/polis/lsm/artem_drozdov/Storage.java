package ru.mail.polis.lsm.artem_drozdov;

import ru.mail.polis.lsm.Record;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public final class Storage {

    private static final NavigableMap<ByteBuffer, Record> EMPTY_STORAGE = newStorage();

    public final NavigableMap<ByteBuffer, Record> storageToFlush;
    public final NavigableMap<ByteBuffer, Record> memoryStorage;

    public final List<SSTable> tables;

    private Storage(NavigableMap<ByteBuffer, Record> memoryStorage,
                    NavigableMap<ByteBuffer, Record> storageToFlush,
                    List<SSTable> tables) {
        this.memoryStorage = memoryStorage;
        this.storageToFlush = storageToFlush;
        this.tables = tables;
    }

    public Storage(List<SSTable> tables) {
        this(newStorage(), EMPTY_STORAGE, tables);
    }

    public Storage prepareBeforeFlush() {
        return new Storage(newStorage(), memoryStorage, tables);
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

    private static NavigableMap<ByteBuffer, Record> newStorage() {
        return new ConcurrentSkipListMap<>();
    }
}
