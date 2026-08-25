package com.springwatch.agent.sql.pool;

import com.springwatch.agent.metric.Histogram;
import com.springwatch.agent.metric.Labels;
import com.springwatch.agent.metric.MetricRegistry;
import net.bytebuddy.asm.Advice;

/**
 * 拦截 {@code com.zaxxer.hikari.pool.HikariPool.createConnection()},
 * 把单条连接的创建耗时喂进 {@code db_client_connections_create_time_milliseconds} 直方图。
 * <p>
 * createConnection 可能是私有方法,ByteBuddy Advice 对私有方法同样内联;
 * 池名通过反射 {@code getPoolName()} 取,失败回退 {@code "default"}。
 */
public final class HikariPoolCreateAdvice {

    private HikariPoolCreateAdvice() {
    }

    @Advice.OnMethodEnter
    public static long onEnter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onCreate(@Advice.Enter long startNanos,
                                @Advice.This Object pool,
                                @Advice.Return Object conn,
                                @Advice.Thrown Throwable thrown) {
        if (thrown != null || conn == null || pool == null) return;
        try {
            long createMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (createMs < 0) return;
            MetricRegistry registry = HikariPoolProbe.registry();
            if (registry == null) return;
            Histogram hist = registry.histograms().get("db_client_connections_create_time_milliseconds");
            if (hist == null) return;
            hist.observe(Labels.of("pool.name", invokePoolName(pool)), (double) createMs);
        } catch (Throwable ignore) {
        }
    }

    public static String invokePoolName(Object pool) {
        try {
            java.lang.reflect.Method m = pool.getClass().getMethod("getPoolName");
            Object v = m.invoke(pool);
            return v == null ? "default" : v.toString();
        } catch (Throwable t) {
            return "default";
        }
    }
}