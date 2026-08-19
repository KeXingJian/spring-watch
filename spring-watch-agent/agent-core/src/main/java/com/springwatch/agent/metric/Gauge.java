package com.springwatch.agent.metric;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * Gauge(瞬时值,每次 scrape 重新读取)。
 * <p>
 * 与 Counter/Histogram 的累积语义不同,Gauge 不存储历史,只持有
 * {@link DoubleSupplier} 引用,抓取时回调读取最新值,适合 JVM 内部
 * 状态(heap/thread count 等)。
 */
public final class Gauge {

    private final String name;
    private final String help;
    private final ConcurrentHashMap<Labels, DoubleSupplier> cells = new ConcurrentHashMap<>();

    public Gauge(String name, String help) {
        this.name = name;
        this.help = help;
    }

    public String name() {
        return name;
    }

    public String help() {
        return help;
    }

    public void register(Labels labels, DoubleSupplier supplier) {
        cells.put(labels, supplier);
    }

    public double value(Labels labels) {
        DoubleSupplier s = cells.get(labels);
        if (s == null) return 0.0;
        try {
            return s.getAsDouble();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    public ConcurrentHashMap<Labels, DoubleSupplier> cells() {
        return cells;
    }
}
