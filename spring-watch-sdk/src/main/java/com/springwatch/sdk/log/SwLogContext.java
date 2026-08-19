package com.springwatch.sdk.log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务侧主动附加日志上下文的轻量 API。
 * <p>
 * 内部使用 InheritableThreadLocal,Agent 的 logback appender 会读取
 * 当前线程上下文并写入 LogEvent。
 */
public final class SwLogContext {

    private static final ThreadLocal<Map<String, String>> CTX = new InheritableThreadLocal<>();

    private SwLogContext() {
    }

    public static void put(String key, String value) {
        if (key == null) {
            return;
        }
        Map<String, String> map = CTX.get();
        if (map == null) {
            map = new LinkedHashMap<>();
            CTX.set(map);
        }
        map.put(key, value);
    }

    public static String get(String key) {
        Map<String, String> map = CTX.get();
        return map == null ? null : map.get(key);
    }

    public static void remove(String key) {
        Map<String, String> map = CTX.get();
        if (map != null) {
            map.remove(key);
        }
    }

    public static void clear() {
        CTX.remove();
    }

    public static Map<String, String> snapshot() {
        Map<String, String> map = CTX.get();
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(map);
    }
}
