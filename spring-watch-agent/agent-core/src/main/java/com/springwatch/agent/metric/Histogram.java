package com.springwatch.agent.metric;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 直方图,支持 count + sum,可选 bucket 分布(便于平台 {@code histogram-quantile} 算出 P50/P95/P99)。
 * <p>
 * 兼容口径:
 * <ul>
 *   <li>无 buckets 时(老 {@code new Histogram(name, help)})输出 {@code _count} / {@code _sum},与原版完全一致,
 *       现有 {@code sw_method_duration_seconds} / {@code sw_sql_duration_seconds} 消费者零影响。</li>
 *   <li>带 buckets 时(新增 {@link #bucketed})输出 {@code _bucket{le=...}} 区间,平台
 *       {@code MetricQueryService.queryHistogramQuantile} 可立即做分位计算,
 *       满足 {@code jvm_gc_duration_seconds} / {@code http_server_request_duration_seconds} 等分位面板。</li>
 * </ul>
 * 向后兼容:老调用方不传 buckets,行为不变。
 */
public final class Histogram {

    private final String name;
    private final String help;
    private final double[] bounds;
    private final ConcurrentHashMap<Labels, Cell> cells = new ConcurrentHashMap<>();

    public Histogram(String name, String help) {
        this(name, help, null);
    }

    public Histogram(String name, String help, double[] bounds) {
        this.name = name;
        this.help = help;
        if (bounds != null) {
            double[] sorted = bounds.clone();
            Arrays.sort(sorted);
            this.bounds = sorted;
        } else {
            this.bounds = null;
        }
    }

    /**
     * 带 bucket 的直方图工厂。bounds 给定后无法修改,observe() 时按 {@code value <= bound[i]} 自增
     * 对应桶(单调累计语义)。最后一桶 {@code +Inf} 由 formatter 渲染,值 = {@code count()}。
     */
    public static Histogram bucketed(String name, String help, double[] bounds) {
        return new Histogram(name, help, bounds);
    }

    public String name() {
        return name;
    }

    public String help() {
        return help;
    }

    public double[] bounds() {
        return bounds;
    }

    public void observe(Labels labels, double value) {
        cells.computeIfAbsent(labels, k -> new Cell(bounds)).observe(value);
    }

    public Cell cell(Labels labels) {
        return cells.computeIfAbsent(labels, k -> new Cell(bounds));
    }

    public ConcurrentHashMap<Labels, Cell> cells() {
        return cells;
    }

    public static final class Cell {
        private final LongAdder count = new LongAdder();
        private final DoubleAccumulator sum = new DoubleAccumulator(Double::sum, 0.0);
        private final long[] bucketCounts;
        private final double[] bounds;

        Cell(double[] bounds) {
            this.bounds = bounds;
            this.bucketCounts = bounds == null ? null : new long[bounds.length];
        }

        void observe(double value) {
            count.increment();
            sum.accumulate(value);
            if (bucketCounts == null) return;
            for (int i = 0; i < bounds.length; i++) {
                if (value <= bounds[i]) bucketCounts[i]++;
            }
        }

        public long count() {
            return count.sum();
        }

        public double sum() {
            return sum.get();
        }

        public long bucketAt(int i) {
            return bucketCounts == null ? 0L : bucketCounts[i];
        }

        public boolean hasBuckets() {
            return bucketCounts != null;
        }
    }
}
