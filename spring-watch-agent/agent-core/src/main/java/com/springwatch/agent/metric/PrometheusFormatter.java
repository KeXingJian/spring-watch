package com.springwatch.agent.metric;

import java.util.Locale;
import java.util.Map;

/**
 * Prometheus 文本格式输出器。
 * <p>
 * 兼容 {@code com.springwatch.collector.parse.OnlinePrometheusParser} 的
 * 解析规则:metric_name{labels} value。
 * <p>
 * 线程安全:render 阶段对 MetricRegistry 做只读遍历,无锁。
 */
public final class PrometheusFormatter {

    private static final String NEWLINE = "\n";

    private PrometheusFormatter() {
    }

    public static String render(MetricRegistry registry) {
        StringBuilder sb = new StringBuilder(8192);

        for (Map.Entry<String, Counter> e : registry.counters().entrySet()) {
            Counter c = e.getValue();
            if (c.help() != null && !c.help().isEmpty()) {
                sb.append("# HELP ").append(c.name()).append(' ').append(c.help()).append(NEWLINE);
            }
            sb.append("# TYPE ").append(c.name()).append(" counter").append(NEWLINE);
            for (Map.Entry<Labels, java.util.concurrent.atomic.LongAdder> cell : c.cells().entrySet()) {
                appendMetric(sb, c.name(), cell.getKey(), String.valueOf(cell.getValue().sum()));
            }
        }

        for (Map.Entry<String, Histogram> e : registry.histograms().entrySet()) {
            Histogram h = e.getValue();
            if (h.help() != null && !h.help().isEmpty()) {
                sb.append("# HELP ").append(h.name()).append(' ').append(h.help()).append(NEWLINE);
            }
            sb.append("# TYPE ").append(h.name()).append(" histogram").append(NEWLINE);
            Map<Labels, Histogram.Cell> cells = h.cells();
            for (Map.Entry<Labels, Histogram.Cell> cell : cells.entrySet()) {
                appendMetric(sb, h.name() + "_count", cell.getKey(), String.valueOf(cell.getValue().count()));
            }
            for (Map.Entry<Labels, Histogram.Cell> cell : cells.entrySet()) {
                appendMetric(sb, h.name() + "_sum", cell.getKey(), formatDouble(cell.getValue().sum()));
            }
            if (h.bounds() != null) {
                double[] bounds = h.bounds();
                for (int i = 0; i < bounds.length; i++) {
                    String le = formatBound(bounds[i]);
                    for (Map.Entry<Labels, Histogram.Cell> cell : cells.entrySet()) {
                        appendMetric(sb, h.name() + "_bucket",
                                withLabel(cell.getKey(), "le", le),
                                String.valueOf(cell.getValue().bucketAt(i)));
                    }
                }
                for (Map.Entry<Labels, Histogram.Cell> cell : cells.entrySet()) {
                    appendMetric(sb, h.name() + "_bucket",
                            withLabel(cell.getKey(), "le", "+Inf"),
                            String.valueOf(cell.getValue().count()));
                }
            }
        }

        for (Map.Entry<String, Gauge> e : registry.gauges().entrySet()) {
            Gauge g = e.getValue();
            if (g.help() != null && !g.help().isEmpty()) {
                sb.append("# HELP ").append(g.name()).append(' ').append(g.help()).append(NEWLINE);
            }
            sb.append("# TYPE ").append(g.name()).append(" gauge").append(NEWLINE);
            for (Map.Entry<Labels, java.util.function.DoubleSupplier> cell : g.cells().entrySet()) {
                appendMetric(sb, g.name(), cell.getKey(), formatDouble(cell.getValue().getAsDouble()));
            }
        }

        return sb.toString();
    }

    private static void appendMetric(StringBuilder sb, String name, Labels labels, String value) {
        sb.append(name);
        if (labels.size() > 0) {
            sb.append('{').append(labels.render()).append('}');
        }
        sb.append(' ').append(value).append(NEWLINE);
    }

    private static Labels withLabel(Labels base, String name, String value) {
        String[] names = base.names().toArray(new String[0]);
        String[] values = base.values().toArray(new String[0]);
        String[] newNames = new String[names.length + 1];
        String[] newValues = new String[values.length + 1];
        System.arraycopy(names, 0, newNames, 0, names.length);
        System.arraycopy(values, 0, newValues, 0, values.length);
        newNames[names.length] = name;
        newValues[values.length] = value;
        return Labels.of(newNames, newValues);
    }

    private static String formatBound(double v) {
        if (v == Math.floor(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.ROOT, "%g", v);
    }

    private static String formatDouble(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "+Inf" : "-Inf";
        if (v == Math.floor(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }
}
