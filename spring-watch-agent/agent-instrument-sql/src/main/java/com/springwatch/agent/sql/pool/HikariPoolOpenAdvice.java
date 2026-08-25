package com.springwatch.agent.sql.pool;

import com.springwatch.agent.metric.Histogram;
import com.springwatch.agent.metric.Labels;
import com.springwatch.agent.metric.MetricRegistry;
import net.bytebuddy.asm.Advice;

/**
 * 拦截 {@code HikariDataSource.getConnection()}:
 * <ul>
 *   <li>enter:记录等待起点;</li>
 *   <li>exit:把阻塞等待时长喂进 {@code db_client_connections_wait_time_milliseconds}
 *       直方图,并在拿到连接后为该连接打时戳(供 close 计算 use 时长)。</li>
 * </ul>
 * 参数全部为 {@code Object};在 {@link HikariPoolProbe#openStarts()} 里以
 * {@code System.identityHashCode(conn)} 索引。注意:连接可能是代理类,但其
 * identityHashCode 在存活期内稳定,close 时取出。
 */
public final class HikariPoolOpenAdvice {

    private HikariPoolOpenAdvice() {
    }

    @Advice.OnMethodEnter
    public static long onEnter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onGetConnection(@Advice.Enter long waitStartNanos,
                                       @Advice.This Object ds,
                                       @Advice.Return Object conn,
                                       @Advice.Thrown Throwable thrown) {
        if (thrown != null || conn == null || ds == null) return;
        try {
            String poolName = invokePoolName(ds);
            long waitMs = (System.nanoTime() - waitStartNanos) / 1_000_000L;
            if (waitMs >= 0) {
                MetricRegistry registry = HikariPoolProbe.registry();
                if (registry != null) {
                    Histogram wait = registry.histograms().get("db_client_connections_wait_time_milliseconds");
                    if (wait != null) {
                        wait.observe(Labels.of("pool.name", poolName), (double) waitMs);
                    }
                }
            }
            long startNanos = System.nanoTime();
            HikariPoolProbe.openStarts().put((long) System.identityHashCode(conn),
                    new HikariPoolProbe.ConnMark(poolName, startNanos));
        } catch (Throwable ignore) {
        }
    }

    public static String invokePoolName(Object ds) {
        try {
            java.lang.reflect.Method m = ds.getClass().getMethod("getPoolName");
            Object v = m.invoke(ds);
            return v == null ? "default" : v.toString();
        } catch (Throwable t) {
            return "default";
        }
    }
}