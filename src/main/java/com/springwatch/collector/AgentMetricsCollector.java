package com.springwatch.collector;

import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMetricsCollector {

    private final KafkaProducerBridge kafkaProducerBridge;
    private final AgentHttpClient agentHttpClient;

    public void collect(MonitorTarget target) {
        String metricsUrl = buildMetricsUrl(target);

        AgentHttpClient.Result result = agentHttpClient.get(metricsUrl);
        if (!result.isOk()) {
            log.warn("[spring-watch: Agent拉取失败 - appid={}, app={}, url={}, error={}]",
                    target.appid(), target.appName(), metricsUrl, result.error());
            return;
        }
        if (result.status() != 200) {
            log.warn("[spring-watch: Agent拉取非200 - appid={}, app={}, url={}, status={}]",
                    target.appid(), target.appName(), metricsUrl, result.status());
            return;
        }
        String body = result.body();
        if (body == null || body.isEmpty()) {
            return;
        }
        long[] metricCount = {0}; //TODO 咱们java非闭包是这样的
        body.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(this::parsePrometheusLine)
                .filter(Objects::nonNull)
                .filter(parsed -> !isOtelInfoMetric(parsed.name))
                .map(parsed -> toMetricEvent(target, parsed, result.status()))
                .forEach(event -> {
                    kafkaProducerBridge.sendMetric(event);
                    metricCount[0]++;
                });
        log.trace("[spring-watch: Agent拉取成功 - appid={}, app={}, url={}, metrics={}]",
                target.appid(), target.appName(), metricsUrl, metricCount[0]);
    }

    private MetricEvent toMetricEvent(MonitorTarget target, ParsedMetric parsed, int status) {
        Map<String, String> tags = new HashMap<>();
//        tags.put("source", "java_agent");
//        tags.put("statusCode", String.valueOf(status));
        return MetricEvent.builder()
                .appid(target.appid())
                .metricName(parsed.name)
//                .method("agent_pull")
                .value(parsed.value)
                .timestamp(Instant.now())
                .tags(parsed.tags)
                .build();
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

    /**
     * kxj: 统一补全 scheme - endpoint 可能是 "host:port" 或 "http://host:port",
     * 喂给 HttpClient 之前必须保证有 scheme,否则报 "invalid URI scheme"
     */
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

    ParsedMetric parsePrometheusLine(String line) {
        try {
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace < 0 || lastSpace >= line.length() - 1) {
                return null;
            }
            String valueStr = line.substring(lastSpace + 1).trim();
            double value = Double.parseDouble(valueStr);

            String metricAndTags = line.substring(0, lastSpace);
            int braceStart = metricAndTags.indexOf('{');
            int braceEnd = metricAndTags.lastIndexOf('}');

            String metricName;
            Map<String, String> tags = new HashMap<>();

            if (braceStart > 0 && braceEnd > braceStart) {
                metricName = metricAndTags.substring(0, braceStart);
                String tagsStr = metricAndTags.substring(braceStart + 1, braceEnd);
                Arrays.stream(tagsStr.split(","))
                        .map(AgentMetricsCollector::parseTagPair)
                        .filter(Objects::nonNull)
                        .forEach(e -> tags.put(e.getKey(), e.getValue()));
            } else {
                metricName = metricAndTags;
            }

            return new ParsedMetric(metricName, tags, value);
        } catch (Exception e) {
            log.debug("[spring-watch: Agent指标解析失败 - line={}, error={}]", line, e.getMessage());
            return null;
        }
    }

    public record ParsedMetric(String name, Map<String, String> tags, double value) {}
    public record MonitorTarget(Long appid, String appName, String endpoint, Integer metricsPort) {}

    private static AbstractMap.SimpleEntry<String, String> parseTagPair(String pair) {
        int eqIdx = pair.indexOf('=');
        if (eqIdx <= 0) return null;
        String key = pair.substring(0, eqIdx).trim();
        String val = pair.substring(eqIdx + 1).trim();
        if (val.startsWith("\"") && val.endsWith("\"")) {
            val = val.substring(1, val.length() - 1);
        }
        return new AbstractMap.SimpleEntry<>(key, val);
    }

    private static boolean isOtelInfoMetric(String name) {
        if ("target_info".equals(name)) {
            log.debug("[spring-watch: 跳过OTel info指标 - name={}, 原因=value恒为1无时序意义,且process_command_args等标签值含双引号会破坏InfluxDB line protocol]",
                    name);
            return true;
        }
        return false;
    }
}
