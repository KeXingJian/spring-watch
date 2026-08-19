package com.springwatch.agent.sql.nativejdbc;

import com.springwatch.agent.boot.JdbcStorage;
import net.bytebuddy.asm.Advice;

/**
 * 拦截 {@code java.sql.Statement.execute*(String)} 系列方法。
 * <p>
 * 字节码内联到 java.sql.Statement 子类(各 JDBC 驱动实现),advice 仅
 * 引用 {@link JdbcStorage}(bootstrap classloader 可见),不引用 agent-core
 * 任何类,避免类加载器分裂。
 */
public final class StatementAdvice {

    private StatementAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Object sqlArg) {
        try {
            JdbcStorage.onStart(Thread.currentThread().getName(), System.nanoTime());
        } catch (Throwable ignore) {
        }
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(@Advice.Argument(0) Object sqlArg, @Advice.Thrown Throwable thrown) {
        try {
            String sql = sqlArg == null ? null : sqlArg.toString();
            JdbcStorage.onEnd(sql, thrown, System.nanoTime());
        } catch (Throwable ignore) {
        }
    }
}
