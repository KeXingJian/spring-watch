package com.springwatch.agent.sql;

import com.springwatch.agent.metric.Counter;
import com.springwatch.agent.metric.Histogram;
import com.springwatch.agent.metric.Labels;
import com.springwatch.agent.metric.MetricRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 进程内的 JDBC 事件导出器。
 * <p>
 * 周期性 drain {@code com.springwatch.agent.boot.JdbcStorage}(bootstrap 隔离
 * 存储),将每个 entry 映射为 sw_sql_* 指标写入 MetricRegistry。
 * <p>
 * 关键点:此处的 JdbcStorage 引用通过 {@code Class.forName(name, true, null)}
 * 显式走 bootstrap classloader,与 advice 内联写入的 QU EUE 是同一份。
 */
public final class JdbcEventExporter {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcEventExporter.class);

    private static final String STORAGE_CLASS = "com.springwatch.agent.boot.JdbcStorage";
    private static final int DRAIN_BATCH = 256;

    private final MetricRegistry registry;
    private final long slowMs;
    private final int digestLimit;

    private final Method drainMethod;

    private final AtomicLong droppedTotal = new AtomicLong(0);

    private volatile ScheduledExecutorService scheduler;

    public JdbcEventExporter(MetricRegistry registry, long slowMs, int digestLimit) {
        this.registry = registry;
        this.slowMs = slowMs;
        this.digestLimit = digestLimit;
        Method drain = null;
        try {
            Class<?> storage = Class.forName(STORAGE_CLASS, true, null);
            drain = storage.getMethod("drain", int.class);
        } catch (Throwable t) {
            LOG.warn("[kxj: P2 JDBC 不可用 - bootstrap class 加载失败 - error={}]", t.getMessage());
        }
        this.drainMethod = drain;
    }

    public void start() {
        if (drainMethod == null) return;
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sw-jdbc-drain");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = s;
        s.scheduleWithFixedDelay(this::drainBatch, 500, 500, TimeUnit.MILLISECONDS);
        LOG.info("[kxj: JdbcEventExporter 启动 - drain interval=500ms, slowMs={}, digestLimit={}]", slowMs, digestLimit);
    }

    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    private void drainBatch() {
        try {
            Object[] flat = (Object[]) drainMethod.invoke(null, DRAIN_BATCH);
            if (flat == null || flat.length == 0) return;
            int n = flat.length / 4;
            for (int i = 0; i < n; i++) {
                String sql = (String) flat[i * 4];
                String errorType = (String) flat[i * 4 + 3];
                long durationNanos = (Long) flat[i * 4 + 2];
                String digest = digest(sql);
                if (digest == null) continue;

                if (digestLimit > 0 && observedKeys() > digestLimit) {
                    droppedTotal.incrementAndGet();
                    continue;
                }

                long durationMs = durationNanos / 1_000_000L;
                Labels label = Labels.of("sql_digest", digest);

                Counter calls = registry.counter(
                        "sw_sql_calls_total", "SQL operations total");
                calls.inc(label);
                if (errorType != null) {
                    Labels errorLabel = Labels.of("sql_digest", digest, "error_type", simpleName(errorType));
                    registry.counter("sw_sql_errors_total", "SQL errors total").inc(errorLabel);
                }
                registry.histogram("sw_sql_duration_seconds", "SQL duration in seconds")
                        .observe(label, durationMs / 1000.0);
                if (durationMs >= slowMs) {
                    registry.counter("sw_sql_slow_total", "SQL slow queries total").inc(Labels.of("sql_digest", digest));
                }
            }
        } catch (Throwable t) {
            LOG.warn("[kxj: JDBC drain 异常 - error={}]", t.getMessage());
        }
    }

    public long droppedTotal() {
        return droppedTotal.get();
    }

    private long observedKeys() {
        Counter c = registry.counters().get("sw_sql_calls_total");
        return c == null ? 0L : c.cells().size();
    }

    private static String digest(String sql) {
        if (sql == null) return null;
        String s = sql.trim();
        if (s.isEmpty()) return null;
        return SqlDigest.digest(s);
    }

    private static String simpleName(String fqn) {
        if (fqn == null) return "none";
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    public static boolean isAvailable() {
        try {
            Class.forName(STORAGE_CLASS, true, null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
