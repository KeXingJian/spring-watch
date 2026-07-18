package com.springwatch.inflight;

import lombok.extern.slf4j.Slf4j;
import org.jctools.queues.MpmcArrayQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

@Slf4j
public class InflightBuffer {

    private final String topic;
    private final int partitionId;
    private final int capacity;
    private final int queueCapacity;
    private final MpmcArrayQueue<Object> ring;
    private final Semaphore slots;
    private final InflightMetrics metrics;

    public InflightBuffer(String topic, int partitionId, int capacity, InflightMetrics metrics) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.topic = topic;
        this.partitionId = partitionId;
        this.capacity = capacity;
        this.queueCapacity = nextPowerOfTwo(capacity + 1);
        this.ring = new MpmcArrayQueue<>(queueCapacity);
        this.slots = new Semaphore(capacity);
        this.metrics = metrics;
    }

    private static int nextPowerOfTwo(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }

    public boolean offer(Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (!slots.tryAcquire()) {
            metrics.rejected(topic, partitionId);
            return false;
        }
        boolean ok = ring.offer(payload);
        if (ok) {
            metrics.updatePending(topic, partitionId, ring.size());
        } else {
            slots.release();
        }
        return ok;
    }

    public int offerBatch(List<Object> payloads) {
        if (payloads == null || payloads.isEmpty()) return 0;
        int n = payloads.size();
        if (!slots.tryAcquire(n)) {
            metrics.rejected(topic, partitionId, n);
            return 0;
        }
        int written = 0;
        try {
            for (Object p : payloads) {
                if (p == null) {
                    throw new IllegalArgumentException("payload must not be null");
                }
                if (!ring.offer(p)) {
                    throw new IllegalStateException(
                        "ring full after tryAcquire(n), invariant broken");
                }
                written++;
            }
        } catch (RuntimeException re) {
            slots.release(n - written);
            metrics.rejected(topic, partitionId, n - written);
            throw re;
        }
        metrics.updatePending(topic, partitionId, ring.size());
        return n;
    }

    public List<Object> drain(int max) {
        if (max <= 0) return List.of();
        List<Object> out = new ArrayList<>(max);
        int n = 0;
        for (int i = 0; i < max; i++) {
            Object e = ring.poll();
            if (e == null) break;
            out.add(e);
            n++;
        }
        if (n > 0) {
            slots.release(n);
            metrics.updatePending(topic, partitionId, ring.size());
        }
        return out;
    }

    public int size() {
        return ring.size();
    }

    public int capacity() {
        return capacity;
    }

}
