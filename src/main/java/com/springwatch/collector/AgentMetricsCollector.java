package com.springwatch.collector;

import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMetricsCollector {

    private final KafkaProducerBridge kafkaProducerBridge;

    public void collect(MonitorTarget target) {
        String metricsUrl = buildMetricsUrl(target);
        long start = System.nanoTime();

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(metricsUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            int statusCode = conn.getResponseCode();
            long costMs = (System.nanoTime() - start) / 1_000_000;

            if (statusCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                int metricCount = 0;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#") || line.isBlank()) {
                        continue;
                    }
                    ParsedMetric parsed = parsePrometheusLine(line);
                    if (parsed != null) {
                        Map<String, String> tags = new HashMap<>(parsed.tags);
                        tags.put("source", "java_agent");
                        tags.put("statusCode", String.valueOf(statusCode));

                        MetricEvent event = MetricEvent.builder()
                                .appid(target.appid())
                                .metricName(parsed.name)
                                .method("agent_pull")
                                .value(parsed.value)
                                .timestamp(Instant.now())
                                .tags(tags)
                                .build();
                        kafkaProducerBridge.sendMetric(event);
                        metricCount++;
                    }
                }
                reader.close();
                log.info("[spring-watch: Agent拉取成功 - appid={}, app={}, url={}, metrics={}, cost={}ms]",
                        target.appid(), target.appName(), metricsUrl, metricCount, costMs);
            } else {
                log.warn("[spring-watch: Agent拉取非200 - appid={}, app={}, url={}, status={}, cost={}ms]",
                        target.appid(), target.appName(), metricsUrl, statusCode, costMs);
            }
            conn.disconnect();

        } catch (Exception e) {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[spring-watch: Agent拉取失败 - appid={}, app={}, url={}, error={}, cost={}ms]",
                    target.appid(), target.appName(), metricsUrl, e.getMessage(), costMs);
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
        return host + ":" + target.metricsPort() + "/metrics";
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
                for (String pair : tagsStr.split(",")) {
                    int eqIdx = pair.indexOf('=');
                    if (eqIdx > 0) {
                        String key = pair.substring(0, eqIdx).trim();
                        String val = pair.substring(eqIdx + 1).trim();
                        if (val.startsWith("\"") && val.endsWith("\"")) {
                            val = val.substring(1, val.length() - 1);
                        }
                        tags.put(key, val);
                    }
                }
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
}
