package com.springwatch.agent.metric;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局指标注册中心。
 * <p>
 * 借鉴 OTel {@code DefaultSynchronousMetricStorage} 的设计思想:
 * 同一 name 在注册表中是单例,内部按 Labels 分桶 key。由 Advice
 * 在 onEnter/onExit 时调用,无需感知底层数据结构。
 */
public final class MetricRegistry {

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Histogram> histograms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Gauge> gauges = new ConcurrentHashMap<>();

    public Counter counter(String name, String help) {
        return counters.computeIfAbsent(name, k -> new Counter(k, help));
    }

    public Histogram histogram(String name, String help) {
        return histograms.computeIfAbsent(name, k -> new Histogram(k, help));
    }

    public Histogram histogram(String name, String help, double[] bounds) {
        return histograms.computeIfAbsent(name, k -> new Histogram(k, help, bounds));
    }

    public Gauge gauge(String name, String help) {
        return gauges.computeIfAbsent(name, k -> new Gauge(k, help));
    }

    public ConcurrentHashMap<String, Counter> counters() {
        return counters;
    }

    public ConcurrentHashMap<String, Histogram> histograms() {
        return histograms;
    }

    public ConcurrentHashMap<String, Gauge> gauges() {
        return gauges;
    }
}
