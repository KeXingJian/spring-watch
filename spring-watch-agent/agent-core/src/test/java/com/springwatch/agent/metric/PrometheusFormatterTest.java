package com.springwatch.agent.metric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusFormatterTest {

    private static String render(MetricRegistry reg) {
        return PrometheusFormatter.render(reg);
    }

    @Test
    void counterLineFormat() {
        MetricRegistry reg = new MetricRegistry();
        Counter c = reg.counter("c_total", "help text");
        c.inc();
        c.inc();
        String out = render(reg);
        assertContains(out, "# HELP c_total help text");
        assertContains(out, "# TYPE c_total counter");
        assertContains(out, "c_total 2");
    }

    @Test
    void histogramCountAndSum() {
        MetricRegistry reg = new MetricRegistry();
        Histogram h = reg.histogram("h_duration_seconds", "dur");
        h.observe(Labels.EMPTY, 1.5);
        h.observe(Labels.EMPTY, 2.5);
        String out = render(reg);
        assertContains(out, "# TYPE h_duration_seconds histogram");
        assertContains(out, "h_duration_seconds_count 2");
        assertContains(out, "h_duration_seconds_sum 4");
    }

    @Test
    void bucketedHistogramEmitsBucketLines() {
        MetricRegistry reg = new MetricRegistry();
        Histogram h = reg.histogram("q_latency", "lat", new double[]{1.0, 5.0});
        h.observe(Labels.EMPTY, 0.5);
        h.observe(Labels.EMPTY, 3.0);
        String out = render(reg);
        assertContains(out, "q_latency_bucket{le=\"1\"} 1");
        assertContains(out, "q_latency_bucket{le=\"5\"} 2");
        assertContains(out, "q_latency_bucket{le=\"+Inf\"} 2");
    }

    @Test
    void labelEscapesQuotesAndBackslashes() {
        MetricRegistry reg = new MetricRegistry();
        Counter c = reg.counter("c_total", "h");
        c.inc(Labels.of("k", "a\"b"));
        String out = render(reg);
        assertContains(out, "c_total{k=\"a\\\"b\"} 1");
    }

    @Test
    void gaugeLineFormat() {
        MetricRegistry reg = new MetricRegistry();
        Gauge g = reg.gauge("g_val", "gauge help");
        g.register(Labels.of("area", "used"), () -> 12.5);
        String out = render(reg);
        assertContains(out, "# TYPE g_val gauge");
        assertContains(out, "g_val{area=\"used\"} 12.5");
    }

    @Test
    void labelWithNewlineEscaped() {
        MetricRegistry reg = new MetricRegistry();
        Counter c = reg.counter("c_total", "h");
        c.inc(Labels.of("k", "line1\nline2"));
        String out = render(reg);
        assertTrue(out.contains("k=\"line1\\nline2\""), out);
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle), "expected output to contain: " + needle + " but was:\n" + haystack);
    }
}
