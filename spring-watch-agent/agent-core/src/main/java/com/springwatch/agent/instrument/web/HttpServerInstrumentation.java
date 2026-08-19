package com.springwatch.agent.instrument.web;

import com.springwatch.agent.config.AgentConfig;
import com.springwatch.agent.instrument.InstrumentDefinition;
import com.springwatch.agent.metric.MetricRegistry;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.LongAdder;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * HTTP server request 仪表化(Spring MVC DispatcherServlet)。
 * <p>
 * 织入点:{@code org.springframework.web.servlet.DispatcherServlet.doDispatch}
 * <ul>
 *   <li>{@code onEnter}:记录起始时间,递增活跃请求计数</li>
 *   <li>{@code onExit}: 读取 URI 模板 / 状态码,累加计数 + 写耗时直方图</li>
 * </ul>
 * 命名对齐平台前端 {@code useAppView.ts:httpOverviewSpecs()} / {@code httpRouteSpecs()}:
 * <pre>
 * http_server_request_duration_seconds_{count,sum,bucket}{http_request_method,http_route,http_response_status_code}
 * http_server_active_requests{gauge}
 * </pre>
 * 参考 OTel Spring Web instrumentation 的 {@code HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}
 * 取路由模板 — 避免裸 URL 撑爆基数。
 * <p>
 * 关闭: {@code -Dspring.watch.disable=http} 关闭;或 {@code -Dspring.watch.otel.namespaces=false} 全局关。
 */
public final class HttpServerInstrumentation implements InstrumentDefinition {

    private static final Logger LOG = LoggerFactory.getLogger(HttpServerInstrumentation.class);

    private static final String DESC_DISPATCHER = "org.springframework.web.servlet.DispatcherServlet";
    private static final String DESC_DO_DISPATCH = "doDispatch";

    private final MetricRegistry registry;

    public HttpServerInstrumentation(MetricRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "http-server";
    }

    @Override
    public ElementMatcher.Junction<TypeDescription> typeMatcher() {
        return named(DESC_DISPATCHER);
    }

    @Override
    public AgentBuilder apply(AgentBuilder builder) {
        if (!AgentConfig.isOtelHttpEnabled()) {
            return builder;
        }
        ActiveRequests.INSTANCE.bind(registry);
        DispatcherAdvice.HttpHistogramHolder.bind(registry,
                "http_server_request_duration_seconds",
                "HTTP server request duration in seconds.",
                DispatcherAdvice.BOUNDS_SEC);
        LOG.info("[kxj: HttpServerInstrumentation 启动 - target=DispatcherServlet.doDispatch]");
        return builder
                .type(typeMatcher())
                .transform((b, td, cl, m, pd) ->
                        b.visit(Advice.to(DispatcherAdvice.class)
                                .on(named(DESC_DO_DISPATCH).and(takesArguments(2)))));
    }

    /**
     * 全局活跃请求计数(并发 in-flight)。在 Advice onEnter / onExit 各加减 1,
     * scrape 时取当快照。
     */
    static final class ActiveRequests {
        static final ActiveRequests INSTANCE = new ActiveRequests();
        private final LongAdder inflight = new LongAdder();
        private volatile MetricRegistry registry;

        void bind(MetricRegistry registry) {
            this.registry = registry;
            registry.gauge("http_server_active_requests", "In-flight HTTP server requests.")
                    .register(com.springwatch.agent.metric.Labels.EMPTY, inflight::longValue);
        }

        void inc() {
            inflight.increment();
        }

        void dec() {
            inflight.decrement();
        }
    }
}
