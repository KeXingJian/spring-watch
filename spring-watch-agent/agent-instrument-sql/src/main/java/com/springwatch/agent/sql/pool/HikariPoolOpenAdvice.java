package com.springwatch.agent.sql.pool;

import net.bytebuddy.asm.Advice;

/**
 * 拦截 {@code HikariDataSource.getConnection()},在拿到连接后为该连接打时戳。
 * <p>
 * 参数全部为 {@code Object};在 {@link HikariPoolProbe#openStarts()} 里以
 * {@code System.identityHashCode(conn)} 索引。注意:连接可能是代理类,但其
 * identityHashCode 在存活期内稳定,close 时取出。
 */
public final class HikariPoolOpenAdvice {

    private HikariPoolOpenAdvice() {
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onGetConnection(@Advice.This Object ds,
                                       @Advice.Return Object conn,
                                       @Advice.Thrown Throwable thrown) {
        if (thrown != null || conn == null || ds == null) return;
        try {
            String poolName = invokePoolName(ds);
            long startNanos = System.nanoTime();
            HikariPoolProbe.openStarts().put((long) System.identityHashCode(conn),
                    new HikariPoolProbe.ConnMark(poolName, startNanos));
        } catch (Throwable ignore) {
        }
    }

    private static String invokePoolName(Object ds) {
        try {
            java.lang.reflect.Method m = ds.getClass().getMethod("getPoolName");
            Object v = m.invoke(ds);
            return v == null ? "default" : v.toString();
        } catch (Throwable t) {
            return "default";
        }
    }
}
