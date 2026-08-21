package com.springwatch.agent.log;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogRingBufferTest {

    private static LogEvent event(String level, String msg) {
        LogEvent e = new LogEvent();
        e.setLevel(level);
        e.setMessage(msg);
        e.setTimestamp(Instant.now());
        return e;
    }

    @Test
    void capacityMustBePowerOf2() {
        assertThrows(IllegalArgumentException.class, () -> new LogRingBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new LogRingBuffer(3));
        assertThrows(IllegalArgumentException.class, () -> new LogRingBuffer(1000));
        new LogRingBuffer(1);
        new LogRingBuffer(2);
        new LogRingBuffer(65536);
    }

    @Test
    void appendAssignsMonotonicSequence() {
        LogRingBuffer buf = new LogRingBuffer(8);
        for (int i = 0; i < 5; i++) {
            buf.append(event("INFO", "msg-" + i));
        }
        LogRingBuffer.Snapshot snap = buf.snapshot();
        assertEquals(5, snap.events.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i, snap.events.get(i).getSequence());
        }
        assertEquals(5, snap.headSequence);
    }

    @Test
    void overflowEvictsOldestAndIncrementsDropCounter() {
        LogRingBuffer buf = new LogRingBuffer(4);
        for (int i = 0; i < 8; i++) {
            buf.append(event("INFO", "msg-" + i));
        }
        assertEquals(4, buf.droppedTotal());
        LogRingBuffer.Snapshot snap = buf.snapshot();
        assertEquals(4, snap.events.size());
        for (int i = 0; i < 4; i++) {
            assertEquals("msg-" + (4 + i), snap.events.get(i).getMessage());
        }
    }

    @Test
    void dropObserverInvokedOnOverwrite() {
        AtomicLong observed = new AtomicLong(0);
        LogRingBuffer buf = new LogRingBuffer(2, level -> observed.incrementAndGet());
        for (int i = 0; i < 5; i++) {
            buf.append(event("WARN", "msg-" + i));
        }
        assertEquals(3, observed.get());
        assertEquals(3, buf.droppedTotal());
    }

    @Test
    void snapshotSinceTimestampExclusive() {
        LogRingBuffer buf = new LogRingBuffer(8);
        Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2024-01-01T00:00:01Z");
        Instant t2 = Instant.parse("2024-01-01T00:00:02Z");
        LogEvent a = event("INFO", "a");
        a.setTimestamp(t0);
        LogEvent b = event("INFO", "b");
        b.setTimestamp(t1);
        LogEvent c = event("INFO", "c");
        c.setTimestamp(t2);
        buf.append(a);
        buf.append(b);
        buf.append(c);

        LogRingBuffer.Snapshot since = buf.snapshotSince(LogRingBuffer.InstantCursor.ofTimestamp(t1));
        assertEquals(1, since.events.size());
        assertEquals("c", since.events.get(0).getMessage());
    }

    @Test
    void snapshotSinceSequenceExclusive() {
        LogRingBuffer buf = new LogRingBuffer(8);
        buf.append(event("INFO", "a"));
        buf.append(event("INFO", "b"));
        buf.append(event("INFO", "c"));
        LogRingBuffer.Snapshot since = buf.snapshotSince(LogRingBuffer.InstantCursor.ofSequence(1));
        assertEquals(1, since.events.size());
        assertEquals("c", since.events.get(0).getMessage());
    }

    @Test
    void concurrentAppendsNoDataLoss() throws InterruptedException {
        int writerCount = 16;
        int perWriter = 1000;
        int capacity = 65536;
        LogRingBuffer buf = new LogRingBuffer(capacity);
        ExecutorService pool = Executors.newFixedThreadPool(writerCount);
        CountDownLatch done = new CountDownLatch(writerCount);
        for (int w = 0; w < writerCount; w++) {
            final int wid = w;
            pool.submit(() -> {
                for (int i = 0; i < perWriter; i++) {
                    buf.append(event("INFO", wid + "-" + i));
                }
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, buf.droppedTotal());
        long expectedTotal = writerCount * perWriter;
        LogRingBuffer.Snapshot snap = buf.snapshot();
        assertEquals(expectedTotal, snap.headSequence);
        assertEquals(expectedTotal, snap.events.size());
        List<Long> seqs = new ArrayList<>();
        for (LogEvent e : snap.events) {
            seqs.add(e.getSequence());
        }
        for (long s = 0; s < expectedTotal; s++) {
            assertTrue(seqs.contains(s), "missing seq " + s);
        }
    }

    @Test
    void snapshotAtomicallyPointsToOldestAtScanStart() {
        LogRingBuffer buf = new LogRingBuffer(4);
        for (int i = 0; i < 4; i++) {
            buf.append(event("INFO", "msg-" + i));
        }
        LogRingBuffer.Snapshot snap = buf.snapshot();
        assertEquals(4, snap.events.size());
        assertEquals(4, snap.headSequence);
        assertEquals(0, snap.tailSequence);
        assertFalse(snap.isEmpty());
    }

    @Test
    void nullEventIgnored() {
        LogRingBuffer buf = new LogRingBuffer(4);
        buf.append(null);
        assertEquals(0, buf.snapshot().events.size());
    }
}
