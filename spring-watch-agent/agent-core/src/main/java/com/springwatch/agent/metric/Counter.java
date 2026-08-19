package com.springwatch.agent.metric;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 计数器,按 Labels 维度分桶。
 * <p>
 * 借鉴 OTel {@code DefaultSynchronousMetricStorage} 的
 * {@code ConcurrentHashMap<Attributes, AggregatorHandle>} 设计,但简化为
 * 单独的 value 字段;无 RESET 操作,值单调递增(行为对齐 Prometheus Counter)。
 */
public final class Counter {

    private final String name;
    private final String help;
    private final ConcurrentHashMap<Labels, LongAdder> cells = new ConcurrentHashMap<>();

    public Counter(String name, String help) {
        this.name = name;
        this.help = help;
    }

    public String name() {
        return name;
    }

    public String help() {
        return help;
    }

    public void inc() {
        inc(Labels.EMPTY);
    }

    public void inc(Labels labels) {
        cells.computeIfAbsent(labels, k -> new LongAdder()).increment();
    }

    public void inc(Labels labels, long delta) {
        if (delta <= 0) return;
        cells.computeIfAbsent(labels, k -> new LongAdder()).add(delta);
    }

    public long sum(Labels labels) {
        LongAdder a = cells.get(labels);
        return a == null ? 0L : a.sum();
    }

    public ConcurrentHashMap<Labels, LongAdder> cells() {
        return cells;
    }
}
