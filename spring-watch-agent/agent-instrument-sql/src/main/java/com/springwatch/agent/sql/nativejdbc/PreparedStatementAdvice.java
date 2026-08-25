package com.springwatch.agent.sql.nativejdbc;

import com.springwatch.agent.boot.JdbcStorage;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Field;

/**
 * 拦截 {@code java.sql.PreparedStatement.execute*()} 系列方法。
 * <p>
 * 与 {@link StatementAdvice} 的差异:PreparedStatement 不以 String SQL
 * 为参数,SQL 缓存在驱动实现的私有字段中(common names: {@code sql} /
 * {@code originalSql} / {@code nativeSql});advice 用反射按多个候选名查找。
 * 反射失败时降级为 {@code <prepared>},仍能给出计数 + 耗时。
 */
public final class PreparedStatementAdvice {

    public static final String PLACEHOLDER = "<prepared>";

    private PreparedStatementAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
        try {
            JdbcStorage.onStart(Thread.currentThread().getName(), System.nanoTime());
        } catch (Throwable ignore) {
        }
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(@Advice.This Object statement, @Advice.Thrown Throwable thrown) {
        try {
            String sql = extractSql(statement);
            JdbcStorage.onEnd(sql, thrown, System.nanoTime());
        } catch (Throwable ignore) {
        }
    }

    public static String extractSql(Object statement) {
        if (statement == null) return PLACEHOLDER;
        Class<?> c = statement.getClass();
        for (String name : new String[]{"sql", "originalSql", "nativeSql", "preparedStatement"}) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(statement);
                if (v instanceof String s && !s.isEmpty()) return s;
            } catch (Throwable ignore) {
            }
        }
        String ts = statement.toString();
        int idx = ts.indexOf("SELECT ");
        if (idx < 0) idx = ts.indexOf("INSERT ");
        if (idx < 0) idx = ts.indexOf("UPDATE ");
        if (idx < 0) idx = ts.indexOf("DELETE ");
        if (idx >= 0) return ts.substring(idx);
        return PLACEHOLDER;
    }
}
