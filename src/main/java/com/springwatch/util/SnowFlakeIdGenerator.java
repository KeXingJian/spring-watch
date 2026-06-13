package com.springwatch.util;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

public final class SnowFlakeIdGenerator {

    private static final long EPOCH = 1622476800000L;

    private static final long WORKER_ID_BITS = 4L;
    private static final long SEQUENCE_BITS = 8L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private static final SnowFlakeIdGenerator INSTANCE;

    static {
        long workerId = 0L;
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            workerId = Math.abs(host.hashCode()) % (MAX_WORKER_ID + 1);
        } catch (Exception ignored) {
        }
        INSTANCE = new SnowFlakeIdGenerator(workerId);
    }

    private final long workerId;
    private final AtomicLong lastTimestamp = new AtomicLong(-1L);
    private final AtomicLong sequence = new AtomicLong(0L);

    private SnowFlakeIdGenerator(long workerId) {
        this.workerId = workerId;
    }

    public static long generateId() {
        return INSTANCE.nextId();
    }

    private synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        long last = lastTimestamp.get();

        if (timestamp < last) {
            timestamp = last;
        }

        if (timestamp == last) {
            long seq = (sequence.incrementAndGet()) & SEQUENCE_MASK;
            if (seq == 0L) {
                timestamp = waitNextMillis(last);
            }
        } else {
            sequence.set(0L);
        }

        lastTimestamp.set(timestamp);

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | (sequence.get() & SEQUENCE_MASK);
    }

    private long waitNextMillis(long last) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= last) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
