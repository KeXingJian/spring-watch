package com.springwatch.agent.instrument.web;

import com.springwatch.agent.metric.Labels;
import com.springwatch.agent.metric.MetricRegistry;

import java.util.concurrent.atomic.LongAdder;

/**
 * 全局活跃请求计数(并发 in-flight)。在 Advice onEnter / onExit 各加减 1,
 * scrape 时取当快照。
 * <p>
 * 独立顶层类:必须与 DispatcherAdvice 一同加载于 bootstrap classloader,
 * 不能嵌套在 HttpServerInstrumentation 内(否则内外层分属不同 classloader,
 * 相互访问抛 IllegalAccessError)。
 */
public final class ActiveRequests {

    public static final ActiveRequests INSTANCE = new ActiveRequests();
    private final LongAdder inflight = new LongAdder();
    private volatile MetricRegistry registry;

    private ActiveRequests() {
    }

    public void bind(MetricRegistry registry) {
        this.registry = registry;
        registry.gauge("http_server_active_requests", "In-flight HTTP server requests.")
                .register(Labels.EMPTY, inflight::longValue);
    }

    public void inc() {
        inflight.increment();
    }

    public void dec() {
        inflight.decrement();
    }
}