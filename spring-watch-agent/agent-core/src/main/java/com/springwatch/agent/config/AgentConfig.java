package com.springwatch.agent.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Agent 全局配置(System property 驱动)。
 * <p>
 * 设计原则:零默认值即"零侵入";所有开关通过 -D 显式声明。
 */
public final class AgentConfig {

    public static final String KEY_METRICS_PORT = "spring.watch.metrics.port";
    public static final String KEY_METRICS_HOST = "spring.watch.metrics.host";
    public static final String KEY_LOG_BUFFER_SIZE = "spring.watch.log.buffer.size";
    public static final String KEY_LOG_APP_NAME = "spring.watch.app.name";
    public static final String KEY_DISABLE = "spring.watch.disable";
    public static final String KEY_TOKEN = "spring.watch.token";
    public static final String KEY_SQL_SLOW_MS = "spring.watch.sql.slow.ms";
    public static final String KEY_SQL_DIGEST_LIMIT = "spring.watch.sql.digest.limit";
    public static final String KEY_METHOD_CARDINALITY_LIMIT = "spring.watch.method.cardinality.limit";

    public static final String KEY_OTEL_NAMESPACES = "spring.watch.otel.namespaces";

    public static final int DEFAULT_METRICS_PORT = 9464;
    public static final String DEFAULT_METRICS_HOST = "0.0.0.0";
    public static final int DEFAULT_LOG_BUFFER_SIZE = 65536;
    public static final long DEFAULT_SQL_SLOW_MS = 500L;
    public static final int DEFAULT_SQL_DIGEST_LIMIT = 2000;
    public static final int DEFAULT_METHOD_CARDINALITY_LIMIT = 5000;

    private AgentConfig() {
    }

    public static int metricsPort() {
        return intOf(KEY_METRICS_PORT, DEFAULT_METRICS_PORT);
    }

    public static String metricsHost() {
        return stringOf(KEY_METRICS_HOST, DEFAULT_METRICS_HOST);
    }

    public static int logBufferSize() {
        int v = intOf(KEY_LOG_BUFFER_SIZE, DEFAULT_LOG_BUFFER_SIZE);
        return Integer.highestOneBit(Math.max(v, 16));
    }

    public static String appName() {
        return stringOf(KEY_LOG_APP_NAME, null);
    }

    public static long sqlSlowMs() {
        return longOf(KEY_SQL_SLOW_MS, DEFAULT_SQL_SLOW_MS);
    }

    public static int sqlDigestLimit() {
        return intOf(KEY_SQL_DIGEST_LIMIT, DEFAULT_SQL_DIGEST_LIMIT);
    }

    public static int methodCardinalityLimit() {
        return intOf(KEY_METHOD_CARDINALITY_LIMIT, DEFAULT_METHOD_CARDINALITY_LIMIT);
    }

    public static String token() {
        return stringOf(KEY_TOKEN, null);
    }

    public static Set<String> disabledCapabilities() {
        String raw = stringOf(KEY_DISABLE, "");
        if (raw.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(Arrays.asList(raw.split(",")));
    }

    public static boolean isLogsEnabled() {
        return !disabledCapabilities().contains("logs");
    }

    public static boolean isMethodEnabled() {
        return !disabledCapabilities().contains("method");
    }

    public static boolean isSqlEnabled() {
        return !disabledCapabilities().contains("sql");
    }

    public static boolean isJvmEnabled() {
        return !disabledCapabilities().contains("jvm");
    }

    /**
     * OTel 风格指标命名空间开关,默认开启。
     * 关掉后只产 {@code sw_*}/{@code sw_jvm_*} 老命名,新指标停止暴露,
     * 用于极简部署或排障时切回老口径。
     */
    public static boolean isOtelNamespacesEnabled() {
        String v = System.getProperty(KEY_OTEL_NAMESPACES, "true");
        if (v == null || v.isBlank()) return true;
        return Boolean.parseBoolean(v.trim());
    }

    public static boolean isOtelJvmEnabled() {
        return isOtelNamespacesEnabled();
    }

    public static boolean isOtelOsEnabled() {
        return isOtelNamespacesEnabled();
    }

    public static boolean isOtelJdbcEnabled() {
        return isOtelNamespacesEnabled();
    }

    public static boolean isOtelHttpEnabled() {
        return isOtelNamespacesEnabled();
    }

    private static int intOf(String key, int def) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long longOf(String key, long def) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String stringOf(String key, String def) {
        String v = System.getProperty(key);
        return v == null ? def : v.trim();
    }
}
