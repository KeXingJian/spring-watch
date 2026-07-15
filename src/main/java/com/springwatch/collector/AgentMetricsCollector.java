package com.springwatch.collector;

import com.springwatch.collector.parse.OnlinePrometheusParser;
import com.springwatch.inflight.InflightProducerBridge;
import com.springwatch.model.event.MetricEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMetricsCollector {

    private final InflightProducerBridge inflightProducerBridge;
    private final AgentHttpClient agentHttpClient;
    private final MeterRegistry meterRegistry;

    private Counter bodyRejectedCounter;

    @PostConstruct
    void init() {
        this.bodyRejectedCounter = Counter.builder("spring.watch.collector.http.body.rejected")
                .description("Agent 指标响应体解析失败/异常次数(对应旧 BatchMetricConsumer 同名指标)")
                .register(meterRegistry);
    }

    public Result collect(MonitorTarget target, int readTimeoutMs) {


        String metricsUrl = buildMetricsUrl(target);

        AgentHttpClient.Result result = agentHttpClient.get(metricsUrl, readTimeoutMs);
        if (!result.isOk()) {
            log.warn("[kxj: Agent拉取失败 - appid={}, app={}, url={}, error={}, latencyMs={}]",
                target.appid(), target.appName(), metricsUrl, result.error(), result.latencyMs());
            return Result.from(result, false);
        }
        if (result.status() != 200) {
            log.warn("[kxj: Agent拉取非200 - appid={}, app={}, url={}, status={}, latencyMs={}]",
                target.appid(), target.appName(), metricsUrl, result.status(), result.latencyMs());
            return Result.from(result, false);
        }
        InputStream body = result.body();
        if (body == null) {
            return Result.from(result, false);
        }
        List<MetricEvent> events = new ArrayList<>(256);
        try {
            OnlinePrometheusParser.parse(body, (name, tags, value) -> {
                if (isOtelInfoMetric(name)) {
                    return;
                }
                events.add(MetricEvent.builder()
                        .appid(target.appid())
                        .metricName(name)
                        .value(value)
                        .timestamp(Instant.now())
                        .tags(tags == null || tags.isEmpty() ? null : tags)
                        .build());
            });
        } catch (Exception e) {
            bodyRejectedCounter.increment();
            log.warn("[kxj: Agent指标解析失败 - appid={}, app={}, error={}]",
                target.appid(), target.appName(), e.getMessage());
            return Result.from(result, false);
        } finally {
            try { body.close(); } catch (Exception ignore) { }
        }
        if (!events.isEmpty()) {
            int accepted = inflightProducerBridge.sendMetricBatch(events);
            if (accepted < events.size()) {
                log.warn("[kxj: 批量入队部分/全部被拒 - appid={}, size={}, accepted={}]",
                    target.appid(), events.size(), accepted);
            }
        }
        log.trace("[kxj: Agent拉取成功 - appid={}, app={}, url={}, metrics={}, latencyMs={}]",
            target.appid(), target.appName(), metricsUrl, events.size(), result.latencyMs());
        return new Result(true, result.status(), result.latencyMs(), result.error());
    }

    public record Result(boolean ok, int status, long latencyMs, String error) {
        public static Result from(AgentHttpClient.Result r, boolean ok) {
            return new Result(ok, r.status(), r.latencyMs(), r.error());
        }
    }

    private String buildMetricsUrl(MonitorTarget target) {
        if (target.endpoint() == null || target.endpoint().isBlank()) {
            return "http://localhost:" + target.metricsPort() + "/metrics";
        }
        String base = target.endpoint().endsWith("/")
                ? target.endpoint().substring(0, target.endpoint().length() - 1)
                : target.endpoint();
        String host = base.replaceFirst(":\\d+", "");
        return normalizeBaseUrl(host) + ":" + target.metricsPort() + "/metrics";
    }

    private static String normalizeBaseUrl(String hostOrUrl) {
        if (hostOrUrl == null || hostOrUrl.isBlank()) {
            return "http://localhost";
        }
        String lower = hostOrUrl.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return hostOrUrl;
        }
        return "http://" + hostOrUrl;
    }

    public record MonitorTarget(Long appid, String appName, String endpoint, Integer metricsPort) {}

    private static boolean isOtelInfoMetric(String name) {
        return "target_info".equals(name);
    }
}
