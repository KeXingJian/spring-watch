package com.springwatch.agent.sql;

import com.springwatch.agent.metric.Counter;
import com.springwatch.agent.metric.Labels;
import com.springwatch.agent.metric.MetricRegistry;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * JDBC 监控 Advice,P1 阶段针对 {@code JdbcTemplate} 拦截。
 * <p>
 * 关键点:仅在系统类加载器织入,不需要 bootstrap 注入,避免 java.sql
 * 拦截的复杂性;P2 阶段以同一模型补齐 java.sql 层。
 * <p>
 * SQL digest 口径:数字/字符串字面量归一化为 ?,只保留骨架(防高基数)。
 */
public final class SqlAdvice {

    private SqlAdvice() {
    }

    public static final String CALLS_NAME = "sw_sql_calls_total";
    public static final String ERRORS_NAME = "sw_sql_errors_total";
    public static final String DURATION_NAME = "sw_sql_duration_seconds";
    public static final String SLOW_NAME = "sw_sql_slow_total";

    private static volatile MetricRegistry REGISTRY;
    private static volatile long SLOW_MS = 500L;
    private static volatile int DIGEST_LIMIT = 2000;
    private static final ConcurrentHashMap<String, LongAdder> EVICTED = new ConcurrentHashMap<>();

    public static void bind(MetricRegistry registry, long slowMs, int digestLimit) {
        REGISTRY = registry;
        SLOW_MS = slowMs;
        DIGEST_LIMIT = digestLimit;
    }

    public static long evictedTotal() {
        long sum = 0L;
        for (LongAdder a : EVICTED.values()) sum += a.sum();
        return sum;
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long onEnter(@Advice.Argument(0) Object sqlArg) {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos,
                               @Advice.Thrown Throwable thrown,
                               @Advice.Argument(0) Object sqlArg) {
        try {
            MetricRegistry registry = REGISTRY;
            if (registry == null) return;

            String digest = SqlDigest.digest(sqlArg == null ? null : sqlArg.toString());
            if (digest == null) return;

            if (DIGEST_LIMIT > 0 && observedKeys(registry) > DIGEST_LIMIT) {
                EVICTED.computeIfAbsent(digest, k -> new LongAdder()).increment();
                return;
            }

            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            Labels label = Labels.of("sql_digest", digest);

            registry.counter(CALLS_NAME, "SQL operations total").inc(label);
            if (thrown != null) {
                Labels errorLabel = Labels.of("sql_digest", digest, "error_type", thrown.getClass().getSimpleName());
                registry.counter(ERRORS_NAME, "SQL errors total").inc(errorLabel);
            }
            registry.histogram(DURATION_NAME, "SQL duration in seconds").observe(label, durationMs / 1000.0);
            if (durationMs >= SLOW_MS) {
                registry.counter(SLOW_NAME, "SQL slow queries total").inc(Labels.of("sql_digest", digest));
            }
        } catch (Throwable ignore) {
        }
    }

    private static long observedKeys(MetricRegistry registry) {
        Counter c = registry.counters().get(CALLS_NAME);
        return c == null ? 0L : c.cells().size();
    }
}
