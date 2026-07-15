package com.springwatch.inflight;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

@Slf4j
public class InflightBuffer {

    private final String topic;
    private final int partitionId;
    private final int capacity;
    private final ArrayBlockingQueue<Object> ring;
    private final Semaphore slots;
    private final InflightMetrics metrics;

    public InflightBuffer(String topic, int partitionId, int capacity, InflightMetrics metrics) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.topic = topic;
        this.partitionId = partitionId;
        this.capacity = capacity;
        this.ring = new ArrayBlockingQueue<>(capacity);
        this.slots = new Semaphore(capacity);
        this.metrics = metrics;
    }

    /**
     * Producer 写入。容量满 → false(背压)。
     * Semaphore.tryAcquire 做 O(1) 无锁快失败;ring.offer 内部加锁写入。
     */
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

    /**
     * 批量 offer。all-or-nothing:容量不够整批 reject,绝不部分写入。
     * 一次 tryAcquire(n) 把 n 个 permit 原子预占;环形数组此时必然有 n 个空位,
     * 逐个 ring.offer 不会失败(否则异常分支 release 全部 permit 回滚)。
     */
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

    /**
     * Consumer 拿走 + 移除(纯内存,无 WAL 竞争)。
     * ring.drainTo 内部加锁批量取出;一次 slots.release(n) 归还 n 个 permit。
     */
    public List<Object> drain(int max) {
        if (max <= 0) return List.of();
        List<Object> out = new ArrayList<>(max);
        int n = ring.drainTo(out, max);
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
