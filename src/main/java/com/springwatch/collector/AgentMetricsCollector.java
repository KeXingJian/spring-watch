package com.springwatch.collector;

import com.springwatch.collector.parse.OnlinePrometheusParser;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMetricsCollector {

    private final KafkaProducerBridge kafkaProducerBridge;
    private final AgentHttpClient agentHttpClient;

    public boolean collect(MonitorTarget target) {
        String metricsUrl = buildMetricsUrl(target);

        AgentHttpClient.Result result = agentHttpClient.get(metricsUrl);
        if (!result.isOk()) {
            log.warn("[kxj: Agent拉取失败 - appid={}, app={}, url={}, error={}]",
                    target.appid(), target.appName(), metricsUrl, result.error());
            return false;
        }
        if (result.status() != 200) {
            log.warn("[kxj: Agent拉取非200 - appid={}, app={}, url={}, status={}]",
                    target.appid(), target.appName(), metricsUrl, result.status());
            return false;
        }
        InputStream body = result.body();
        if (body == null) {
            return false;
        }
        final long[] metricCount = {0};
        try {
            OnlinePrometheusParser.parse(body, (name, tags, value) -> {
                if (isOtelInfoMetric(name)) {
                    return;
                }
                MetricEvent event = MetricEvent.builder()
                        .appid(target.appid())
                        .metricName(name)
                        .value(value)
                        .timestamp(Instant.now())
                        .tags(tags == null || tags.isEmpty() ? null : tags)
                        .build();
                kafkaProducerBridge.sendMetric(event);
                metricCount[0]++;
            });
        } catch (Exception e) {
            log.warn("[kxj: Agent指标解析失败 - appid={}, app={}, error={}]",
                    target.appid(), target.appName(), e.getMessage());
            return false;
        } finally {
            try { body.close(); } catch (Exception ignore) { }
        }
        log.trace("[kxj: Agent拉取成功 - appid={}, app={}, url={}, metrics={}]",
                target.appid(), target.appName(), metricsUrl, metricCount[0]);
        return true;
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
