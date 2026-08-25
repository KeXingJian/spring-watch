package com.springwatch.agent.sql.pool;

import com.springwatch.agent.metric.Labels;
import com.springwatch.agent.metric.MetricRegistry;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hikari 数据源引用跟踪 + gauge 注册中心。
 * <p>
 * <b>为什么走反射:</b> 本类运行于 bootstrap classloader(随 agent jar 一起被
 * {@code appendToBootstrapClassLoaderSearch} 注入),不能直接 import
 * {@code com.zaxxer.hikari.*};否则在客户环境没有 Hikari 依赖时 Advice 类加载即抛
 * {@link NoClassDefFoundError}。所有对 Hikari 实例的访问走反射;失败一律 catch-ignore。
 * <p>
 * <b>使用约定:</b>
 * <ul>
 *   <li>{@link #bind(MetricRegistry)} 在 AppContext 启动时调用,绑定唯一 registry</li>
 *   <li>{@link #register(Object)} 由 Advice 在 HikariDataSource 构造后调用注册 gauge</li>
 *   <li>{@link #openStarts()} 仅供 OpenAdvice / CloseAdvice 用 — 维护
 *       {@code System.identityHashCode(conn) -> ConnMark} 的映射</li>
 *   <li>{@link #registry()} 仅供 CloseAdvice 用 — 拿 registry 写 use_time histogram</li>
 * </ul>
 */
public final class HikariPoolProbe {

    private static final double[] USE_TIME_BOUNDS_MS =
            {1d, 5d, 10d, 25d, 50d, 100d, 250d, 500d, 1000d, 2500d, 5000d};

    private static final Map<Object, String> POOL_NAMES = new ConcurrentHashMap<>();
    private static final Map<Long, ConnMark> OPEN_STARTS = new ConcurrentHashMap<>();
    private static volatile MetricRegistry REGISTRY;

    private HikariPoolProbe() {
    }

    public static void bind(MetricRegistry registry) {
        REGISTRY = registry;
    }

    public static Map<Long, ConnMark> openStarts() {
        return OPEN_STARTS;
    }

    public static MetricRegistry registry() {
        return REGISTRY;
    }

    public static void register(Object ds) {
        if (ds == null) return;
        MetricRegistry registry = REGISTRY;
        if (registry == null) return;

        if (POOL_NAMES.putIfAbsent(ds, "*") != null) return;
        String poolName = invokeString(ds, "getPoolName", "default");

        Labels pool = Labels.of("pool.name", poolName);

        registry.gauge("db_client_connections_max",
                "Configured maximum pool size.").register(pool, () -> invokeInt(ds, "getMaximumPoolSize", 0));
        registry.gauge("db_client_connections_min",
                "Configured minimum idle pool size.").register(pool, () -> invokeInt(ds, "getMinimumIdle", 0));
        registry.gauge("db_client_connections_idle_min",
                "Configured minimum idle pool size (alias).").register(pool, () -> invokeInt(ds, "getMinimumIdle", 0));
        registry.gauge("db_client_connections_usage",
                "Pool connections by state.")
                .register(withState(pool, "idle"), () -> invokeMx(ds, "getIdleConnections"));
        registry.gauge("db_client_connections_usage",
                "Pool connections by state.")
                .register(withState(pool, "used"), () -> invokeMx(ds, "getActiveConnections"));
        registry.gauge("db_client_connections_pending_requests",
                "Threads currently waiting for a connection.")
                .register(pool, () -> invokeMx(ds, "getThreadsAwaitingConnection"));

        registry.histogram("db_client_connections_use_time_milliseconds",
                "Time an application held a DB connection (ms).",
                USE_TIME_BOUNDS_MS);
        registry.histogram("db_client_connections_wait_time_milliseconds",
                "Time spent waiting to acquire a connection from the pool (ms).",
                USE_TIME_BOUNDS_MS);
        registry.histogram("db_client_connections_create_time_milliseconds",
                "Time taken to create a new connection (ms).",
                USE_TIME_BOUNDS_MS);
    }

    private static Labels withState(Labels base, String state) {
        String[] names = base.names().toArray(new String[0]);
        String[] values = base.values().toArray(new String[0]);
        String[] newNames = new String[names.length + 1];
        String[] newValues = new String[values.length + 1];
        System.arraycopy(names, 0, newNames, 0, names.length);
        System.arraycopy(values, 0, newValues, 0, values.length);
        newNames[names.length] = "state";
        newValues[values.length] = state;
        return Labels.of(newNames, newValues);
    }

    private static String invokeString(Object target, String method, String fallback) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            return v == null ? fallback : v.toString();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static double invokeInt(Object target, String method, int fallback) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            return v instanceof Number ? ((Number) v).doubleValue() : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * 反射读 {@code HikariDataSource.getHikariPoolMXBean()} 然后调同名方法(如
     * {@code getIdleConnections})。mx bean 不存在(未初始化)或方法缺失一律 0d。
     */
    private static double invokeMx(Object ds, String method) {
        try {
            Method mxM = ds.getClass().getMethod("getHikariPoolMXBean");
            Object mx = mxM.invoke(ds);
            if (mx == null) return 0d;
            Method m = mx.getClass().getMethod(method);
            Object v = m.invoke(mx);
            return v instanceof Number ? ((Number) v).doubleValue() : 0d;
        } catch (Throwable t) {
            return 0d;
        }
    }

    public record ConnMark(String poolName, long startNanos) {}
}
