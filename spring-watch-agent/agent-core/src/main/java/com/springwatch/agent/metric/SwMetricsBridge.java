package com.springwatch.agent.metric;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 反射桥接 {@code com.springwatch.sdk.metric.SwMetricsRecorder},把业务侧
 * 手动埋点(SwMetricsRecorder.counterInc / histogramObserve / gaugeSet)汇入
 * Agent 的 {@link MetricRegistry},随 /metrics 拉取。
 * <p>
 * 关键点:SDK 为可选依赖,Agent 编译期不 import;通过系统类加载器 + JDK Proxy
 * 动态实现 MetricsBackend,SDK 缺失时静默降级(NOOP),业务不受影响。
 */
public final class SwMetricsBridge {

    private static final Logger LOG = LoggerFactory.getLogger(SwMetricsBridge.class);

    private static final String RECORDER_CLASS = "com.springwatch.sdk.metric.SwMetricsRecorder";
    private static final String BACKEND_CLASS = "com.springwatch.sdk.metric.SwMetricsRecorder$MetricsBackend";

    private static final ConcurrentHashMap<Labels, AtomicReference<Double>> GAUGE_VALUES = new ConcurrentHashMap<>();

    private SwMetricsBridge() {
    }

    public static void register(MetricRegistry registry) {
        try {
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            Class<?> recorder = Class.forName(RECORDER_CLASS, true, sys);
            Class<?> backend = Class.forName(BACKEND_CLASS, true, sys);

            Object proxy = Proxy.newProxyInstance(sys, new Class<?>[]{backend}, (p, method, args) -> {
                try {
                    switch (method.getName()) {
                        case "counterInc":
                            counterInc(registry, (String) args[0], labelPairs(args));
                            return null;
                        case "histogramObserve":
                            histogramObserve(registry, (String) args[0], ((Number) args[1]).doubleValue(), labelPairs(args, 2));
                            return null;
                        case "gaugeSet":
                            gaugeSet(registry, (String) args[0], ((Number) args[1]).doubleValue(), labelPairs(args, 2));
                            return null;
                        default:
                            return null;
                    }
                } catch (Throwable t) {
                    LOG.debug("[kxj: SwMetricsRecorder 桥接处理失败 - method={}, error={}]", method.getName(), t.getMessage());
                    return null;
                }
            });

            recorder.getMethod("register", backend).invoke(null, proxy);
            LOG.info("[kxj: SwMetricsRecorder 桥接注册成功 - 业务手动指标已汇入 Agent 注册表]");
        } catch (Throwable t) {
            LOG.debug("[kxj: SwMetricsRecorder 桥接失败 - SDK 未在 classpath - error={}]", t.getMessage());
        }
    }

    private static void counterInc(MetricRegistry registry, String name, Labels labels) {
        registry.counter(sanitize(name), name).inc(labels);
    }

    private static void histogramObserve(MetricRegistry registry, String name, double value, Labels labels) {
        registry.histogram(sanitize(name), name).observe(labels, value);
    }

    private static void gaugeSet(MetricRegistry registry, String name, double value, Labels labels) {
        AtomicReference<Double> holder = GAUGE_VALUES.computeIfAbsent(labels, k -> new AtomicReference<>(value));
        holder.set(value);
        registry.gauge(sanitize(name), name).register(labels, holder::get);
    }

    /**
     * Prometheus / InfluxDB 指标名只允许 [a-zA-Z0-9_:],点号等非法字符替换为下划线。
     */
    private static String sanitize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == ':' || (i == 0 && ch == '.')) {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static Labels labelPairs(Object[] args) {
        return labelPairs(args, 0);
    }

    private static Labels labelPairs(Object[] args, int offset) {
        String[] pairs = args.length > offset && args[offset] instanceof String[] ? (String[]) args[offset] : null;
        if (pairs == null || pairs.length == 0) {
            return Labels.EMPTY;
        }
        String[] names = new String[pairs.length / 2];
        String[] values = new String[pairs.length / 2];
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            names[i / 2] = pairs[i];
            values[i / 2] = pairs[i + 1];
        }
        return Labels.of(names, values);
    }
}