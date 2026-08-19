package com.springwatch.agent.sql.pool;

import com.springwatch.agent.metric.Histogram;
import com.springwatch.agent.metric.Labels;
import com.springwatch.agent.metric.MetricRegistry;
import net.bytebuddy.asm.Advice;

/**
 * 拦截 {@code HikariProxyConnection.close()},计算 use 时长并发到直方图。
 * <p>
 * 用 {@link System#identityHashCode(Object)} 在 {@link HikariPoolProbe#openStarts()} 里
 * 找出这条连接在 {@code getConnection} 时记录的起始 nanos;超过 1 小时则认作"陈旧条目,
 * 直接丢弃,避免误累加。
 */
public final class HikariPoolCloseAdvice {

    private HikariPoolCloseAdvice() {
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onClose(@Advice.This Object proxy) {
        try {
            HikariPoolProbe.ConnMark mark = HikariPoolProbe.openStarts()
                    .remove((long) System.identityHashCode(proxy));
            if (mark == null) return;
            MetricRegistry registry = HikariPoolProbe.registry();
            if (registry == null) return;

            long durationMs = (System.nanoTime() - mark.startNanos()) / 1_000_000L;
            if (durationMs < 0 || durationMs > 3_600_000L) return;

            Histogram hist = registry.histograms().get("db_client_connections_use_time_milliseconds");
            if (hist == null) return;
            hist.observe(Labels.of("pool.name", mark.poolName()), (double) durationMs);
        } catch (Throwable ignore) {
        }
    }
}
