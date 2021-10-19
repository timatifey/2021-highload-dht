package ru.mail.polis.lsm.artem_drozdov;

import ru.mail.polis.lsm.Record;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public class MemTable {

    private final NavigableMap<ByteBuffer, Record> map = new ConcurrentSkipListMap<>();
    private final AtomicLong size = new AtomicLong();

    private SortedMap<ByteBuffer, Record> map(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        if (fromKey == null && toKey == null) {
            return map;
        }
        if (fromKey == null) {
            return map.headMap(toKey);
        }
        if (toKey == null) {
            return map.tailMap(fromKey);
        }
        return map.subMap(fromKey, toKey);
    }

    public Iterator<Record> range(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        return map(fromKey, toKey).values().iterator();
    }

    public long putAndGetSize(Record record) {
        Record prevRecord = map.put(record.getKey(), record);
        return size.addAndGet(sizeOf(record) - sizeOf(prevRecord));
    }

    private static long sizeOf(@Nullable Record record) {
        if (record == null) {
            return 0;
        }
        int keySize = Integer.BYTES + record.getKeySize();
        int valueSize = Integer.BYTES + record.getValueSize();
        return keySize + valueSize;
    }

}
