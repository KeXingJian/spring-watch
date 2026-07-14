package com.springwatch.inflight;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class InflightBuffer {

    private final String topic;
    private final int partitionId;
    private final int capacity;
    private final ArrayDeque<Object> ring = new ArrayDeque<>();
    private final ReentrantLock mutex = new ReentrantLock();
    private final Semaphore slots;
    private final InflightMetrics metrics;

    public InflightBuffer(String topic, int partitionId, int capacity, InflightMetrics metrics) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.topic = topic;
        this.partitionId = partitionId;
        this.capacity = capacity;
        this.slots = new Semaphore(capacity);
        this.metrics = metrics;
    }

    /** Producer 写入。容量满 → false(背压) */
    public boolean offer(Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (!slots.tryAcquire()) {
            metrics.rejected(topic, partitionId);
            return false;
        }
        mutex.lock();
        try {
            ring.offerLast(payload);
            metrics.updatePending(topic, partitionId, ring.size());
            return true;
        } finally {
            mutex.unlock();
        }
    }

    /**
     * Consumer 拿走 + 移除(纯内存,无 WAL 竞争)。
     * 返回的 List 是 ring 的副本(已 pollFirst 完),调用方处理完即可丢弃。
     */
    public List<Object> drain(int max) {
        if (max <= 0) return List.of();
        mutex.lock();
        try {
            int n = Math.min(max, ring.size());
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Object o = ring.pollFirst();
                if (o != null) {
                    out.add(o);
                    slots.release();
                }
            }
            metrics.updatePending(topic, partitionId, ring.size());
            return out;
        } finally {
            mutex.unlock();
        }
    }

    public int size() {
        mutex.lock();
        try {
            return ring.size();
        } finally {
            mutex.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    public int availablePermits() {
        return slots.availablePermits();
    }
}
