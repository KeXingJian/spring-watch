package com.springwatch.agent.sql.pool;

import net.bytebuddy.asm.Advice;

/**
 * 拦截 {@code HikariDataSource} 构造函数,把新实例回填到 {@link HikariPoolProbe}。
 * <p>
 * 参数全部为 {@code Object},不直接 import Hikari 类型 — agent 的 Advice 类
 * 加载于 bootstrap classloader,反射方式访问具体类型,避免客户环境无 Hikari 依赖时崩溃。
 */
public final class HikariPoolCtorAdvice {

    private HikariPoolCtorAdvice() {
    }

    @Advice.OnMethodExit
    public static void onConstruct(@Advice.This Object ds) {
        try {
            HikariPoolProbe.register(ds);
        } catch (Throwable ignore) {
        }
    }
}
