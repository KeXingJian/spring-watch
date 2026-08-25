package com.springwatch.agent.instrument.web;

import com.springwatch.agent.metric.Histogram;
import com.springwatch.agent.metric.MetricRegistry;

/**
 * HTTP 耗时直方图注册中心。
 * <p>
 * 独立顶层类:与 DispatcherAdvice 同域加载于 bootstrap classloader;
 * 供 DispatcherAdvice(织入业务类)与 HttpServerInstrumentation(app
 * classloader 装配)共享,避免跨 classloader 包访问。
 */
public final class HttpHistogramHolder {

    public static final double[] BOUNDS_SEC = {
            0.005d, 0.01d, 0.025d, 0.05d, 0.1d, 0.25d, 0.5d, 1d, 2.5d, 5d, 10d
    };

    private static volatile Histogram INSTANCE;

    private HttpHistogramHolder() {
    }

    public static Histogram get() {
        return INSTANCE;
    }

    public static synchronized void bind(MetricRegistry registry, String name, String help, double[] bounds) {
        if (INSTANCE != null) return;
        INSTANCE = registry.histogram(name, help, bounds);
    }
}