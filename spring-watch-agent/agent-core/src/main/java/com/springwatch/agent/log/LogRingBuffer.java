package com.springwatch.agent.log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 无锁日志环形缓冲(替代 v1.2 ConcurrentLinkedDeque + 静默丢)。
 * <p>
 * 设计要点:
 * <ul>
 *   <li>固定容量,2 的幂,用掩码取模,内存布局紧凑无链表指针</li>
 *   <li>写路径仅一次 {@link AtomicLong#getAndIncrement} + volatile 顺序发布 event → sequence</li>
 *   <li>覆写最旧时回调 {@link DropObserver},业务层可挂指标;默认 no-op</li>
 *   <li>读路径为 read-only 快照:不阻塞写;按单调 seq 范围扫描,与 logback 线程无锁冲突</li>
 *   <li>sequence 在 Agent 进程内单调递增,作为双轨游标(向后兼容 ISO timestamp)</li>
 * </ul>
 *
 * <p>借鉴 OTel BSP 的"丢要可观测"思想 + LMAX Disruptor 的"环形 + 单调序列"思想,但实现极简不引入外部依赖。
 */
public final class LogRingBuffer {

    private final int capacity;
    private final int mask;
    private final Slot[] slots;
    private final AtomicLong writeSequence = new AtomicLong(0);
    private final AtomicLong droppedTotal = new AtomicLong(0);
    private final DropObserver dropObserver;

    public LogRingBuffer(int capacity) {
        this(capacity, DropObserver.NOOP);
    }

    public LogRingBuffer(int capacity, DropObserver dropObserver) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be power of 2, got: " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.slots = new Slot[capacity];
        for (int i = 0; i < capacity; i++) {
            this.slots[i] = new Slot();
        }
        this.dropObserver = dropObserver == null ? DropObserver.NOOP : dropObserver;
    }

    public int capacity() {
        return capacity;
    }

    public long droppedTotal() {
        return droppedTotal.get();
    }

    public void append(LogEvent event) {
        if (event == null) {
            return;
        }
        long seq = writeSequence.getAndIncrement();
        int idx = (int) (seq & mask);
        Slot slot = slots[idx];
        LogEvent previous = slot.getEvent();
        event.setSequence(seq);
        slot.setEvent(event);
        slot.setSequence(seq);

        if (previous != null && seq >= capacity) {
            droppedTotal.incrementAndGet();
            String level = previous.getLevel() == null ? "UNKNOWN" : previous.getLevel();
            dropObserver.onDropped(level);
        }
    }

    public Snapshot snapshot() {
        long head = writeSequence.get();
        long tail = Math.max(0L, head - capacity);
        return snapshotRange(tail, head);
    }

    public Snapshot snapshotSince(InstantCursor since) {
        Snapshot all = snapshot();
        if (since == null) {
            return all;
        }
        if (since.sequence != null) {
            long startExclusive = Math.max(since.sequence + 1, all.tailSequence);
            return snapshotRange(startExclusive, all.headSequence);
        }
        if (since.timestamp != null) {
            List<LogEvent> filtered = new ArrayList<>(all.events.size());
            for (LogEvent e : all.events) {
                if (e.getTimestamp() != null && e.getTimestamp().isAfter(since.timestamp)) {
                    filtered.add(e);
                }
            }
            return new Snapshot(filtered, all.headSequence, all.tailSequence);
        }
        return all;
    }

    private Snapshot snapshotRange(long fromSeq, long toSeq) {
        if (fromSeq < 0) fromSeq = 0;
        if (toSeq > writeSequence.get()) toSeq = writeSequence.get();
        if (toSeq <= fromSeq) {
            return new Snapshot(List.of(), toSeq, Math.max(0L, toSeq - capacity));
        }
        List<LogEvent> events = new ArrayList<>((int) (toSeq - fromSeq));
        for (long seq = fromSeq; seq < toSeq; seq++) {
            Slot slot = slots[(int) (seq & mask)];
            if (slot.getSequence() != seq) {
                continue;
            }
            LogEvent event = slot.getEvent();
            if (event != null) {
                events.add(event);
            }
        }
        return new Snapshot(events, toSeq, Math.max(0L, toSeq - capacity));
    }

    public static final class Snapshot {
        public final List<LogEvent> events;
        public final long headSequence;
        public final long tailSequence;

        Snapshot(List<LogEvent> events, long headSequence, long tailSequence) {
            this.events = events;
            this.headSequence = headSequence;
            this.tailSequence = tailSequence;
        }

        public boolean isEmpty() {
            return events.isEmpty();
        }
    }

    public static final class InstantCursor {
        public final java.time.Instant timestamp;
        public final Long sequence;

        public InstantCursor(java.time.Instant timestamp, Long sequence) {
            this.timestamp = timestamp;
            this.sequence = sequence;
        }

        public static InstantCursor ofTimestamp(java.time.Instant ts) {
            return new InstantCursor(ts, null);
        }

        public static InstantCursor ofSequence(long seq) {
            return new InstantCursor(null, seq);
        }
    }

    public interface DropObserver {
        DropObserver NOOP = level -> { };

        void onDropped(String level);
    }

    private static final class Slot {
        private volatile LogEvent event;
        private volatile long sequence;

        LogEvent getEvent() {
            return event;
        }

        void setEvent(LogEvent event) {
            this.event = event;
        }

        long getSequence() {
            return sequence;
        }

        void setSequence(long sequence) {
            this.sequence = sequence;
        }
    }
}
