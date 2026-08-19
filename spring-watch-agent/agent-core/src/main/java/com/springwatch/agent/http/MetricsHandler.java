package com.springwatch.agent.http;

import com.springwatch.agent.metric.MetricRegistry;
import com.springwatch.agent.metric.PrometheusFormatter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * GET /metrics — Prometheus 文本格式。
 * <p>
 * 平台 {@code AgentMetricsCollector} 走流式解析,新增 sw_* 指标天然兼容。
 */
public final class MetricsHandler extends BaseHandler {

    private final MetricRegistry registry;

    public MetricsHandler(MetricRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            writeEmpty(ex, 405);
            return;
        }
        try {
            String body = PrometheusFormatter.render(registry);
            writeText(ex, 200, body);
        } catch (Exception e) {
            log.warn("[kxj: /metrics 渲染异常 - error={}]", e.getMessage());
            writeEmpty(ex, 500);
        }
    }
}
