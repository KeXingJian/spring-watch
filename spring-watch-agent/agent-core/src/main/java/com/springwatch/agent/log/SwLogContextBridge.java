package com.springwatch.agent.log;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 反射桥接 {@code com.springwatch.sdk.log.SwLogContext},避免 Agent 编译期
 * 硬依赖 SDK。当 SDK 不在 classpath 时,snapshot() 始终返回空 map,业务不受影响。
 */
final class SwLogContextBridge {

    private static final Method SNAPSHOT_METHOD;

    static {
        Method m = null;
        try {
            Class<?> clazz = Class.forName("com.springwatch.sdk.log.SwLogContext");
            m = clazz.getMethod("snapshot");
        } catch (Throwable ignore) {
        }
        SNAPSHOT_METHOD = m;
    }

    private SwLogContextBridge() {
    }

    static Map<String, String> snapshot() {
        if (SNAPSHOT_METHOD == null) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) SNAPSHOT_METHOD.invoke(null);
            return result == null ? Map.of() : result;
        } catch (Throwable t) {
            return Map.of();
        }
    }
}
