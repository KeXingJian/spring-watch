package com.springwatch.agent.metric;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricRegistryTest {

    @Test
    void counterIncCreatesLabelBucket() {
        MetricRegistry reg = new MetricRegistry();
        Counter c = reg.counter("sw_test_total", "help");
        c.inc(Labels.of("k", "v"));
        c.inc(Labels.of("k", "v"));
        c.inc(Labels.of("k", "other"));
        assertEquals(2L, c.sum(Labels.of("k", "v")));
        assertEquals(1L, c.sum(Labels.of("k", "other")));
    }

    @Test
    void histogramCountAndSum() {
        MetricRegistry reg = new MetricRegistry();
        Histogram h = reg.histogram("sw_dur", "help");
        h.observe(Labels.EMPTY, 1.0);
        h.observe(Labels.EMPTY, 3.0);
        Histogram.Cell cell = h.cell(Labels.EMPTY);
        assertEquals(2L, cell.count());
        assertEquals(4.0, cell.sum(), 1e-9);
    }

    @Test
    void gaugeCallsSupplierOnRead() {
        MetricRegistry reg = new MetricRegistry();
        Gauge g = reg.gauge("sw_gauge", "help");
        AtomicReference<Double> val = new AtomicReference<>(5.0);
        g.register(Labels.EMPTY, val::get);
        assertEquals(5.0, g.value(Labels.EMPTY), 1e-9);
        val.set(9.5);
        assertEquals(9.5, g.value(Labels.EMPTY), 1e-9);
    }

    @Test
    void concurrentIncAccurate() throws InterruptedException {
        MetricRegistry reg = new MetricRegistry();
        Counter c = reg.counter("sw_conc", "help");
        int threads = 100;
        int perThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    c.inc(Labels.EMPTY);
                }
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(100_000L, c.sum(Labels.EMPTY));
    }

    @Test
    void registryReturnsSingletonPerName() {
        MetricRegistry reg = new MetricRegistry();
        assertSame(reg.counter("a", "h"), reg.counter("a", "h"));
        assertSame(reg.histogram("b", "h"), reg.histogram("b", "h"));
        assertSame(reg.gauge("c", "h"), reg.gauge("c", "h"));
    }

    @Test
    void counterDeltaIgnoredWhenNonPositive() {
        MetricRegistry reg = new MetricRegistry();
        Counter c = reg.counter("sw_delta", "help");
        c.inc(Labels.EMPTY, 5);
        c.inc(Labels.EMPTY, 0);
        c.inc(Labels.EMPTY, -3);
        assertEquals(5L, c.sum(Labels.EMPTY));
    }
}
