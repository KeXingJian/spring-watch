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
public class PrometheusScraper {

    private final KafkaProducerBridge kafkaProducerBridge;

    public void scrape(MonitorAppScraper target) {
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
                        tags.put("statusCode", String.valueOf(statusCode));

                        MetricEvent event = MetricEvent.builder()
                                .appName(target.appName())
                                .metricName(parsed.name)
                                .method("prometheus_scrape")
                                .value(parsed.value)
                                .timestamp(Instant.now())
                                .tags(tags)
                                .build();
                        kafkaProducerBridge.sendMetric(event);
                        metricCount++;
                    }
                }
                reader.close();
                log.info("[spring-watch: Prometheus拉取成功 - app={}, url={}, metrics={}, cost={}ms]",
                        target.appName(), metricsUrl, metricCount, costMs);
            } else {
                log.warn("[spring-watch: Prometheus拉取非200 - app={}, url={}, status={}, cost={}ms]",
                        target.appName(), metricsUrl, statusCode, costMs);
                sendProbeMetric(target, metricsUrl, costMs, statusCode);
            }
            conn.disconnect();

        } catch (Exception e) {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[spring-watch: Prometheus拉取失败 - app={}, url={}, error={}, cost={}ms]",
                    target.appName(), metricsUrl, e.getMessage(), costMs);
            sendProbeMetric(target, metricsUrl, costMs, -1);
        }
    }

    private String buildMetricsUrl(MonitorAppScraper target) {
        String endpoint = target.endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return "http://localhost:8080/metrics";
        }
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        Integer metricsPort = target.metricsPort();
        if (metricsPort != null && metricsPort > 0) {
            String host = base.replaceFirst(":\\d+", "");
            return host + ":" + metricsPort + "/metrics";
        }
        return base + "/metrics";
    }

    private void sendProbeMetric(MonitorAppScraper target, String url, long costMs, int statusCode) {
        Map<String, String> tags = new HashMap<>();
        tags.put("url", url);
        if (statusCode > 0) {
            tags.put("statusCode", String.valueOf(statusCode));
        } else {
            tags.put("statusCode", "error");
        }

        MetricEvent event = MetricEvent.builder()
                .appName(target.appName())
                .metricName("scrape_probe")
                .method("prometheus_scrape")
                .value((double) costMs)
                .timestamp(Instant.now())
                .tags(tags)
                .build();
        kafkaProducerBridge.sendMetric(event);
    }

    ParsedMetric parsePrometheusLine(String line) {
        try {
            String metricName;
            Map<String, String> tags = new HashMap<>();
            double value;

            int braceStart = line.indexOf('{');
            int braceEnd = line.indexOf('}');

            if (braceStart > 0 && braceEnd > braceStart) {
                metricName = line.substring(0, braceStart);
                String tagsStr = line.substring(braceStart + 1, braceEnd);
                String[] pairs = tagsStr.split(",");
                for (String pair : pairs) {
                    pair = pair.trim();
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
                String valueStr = line.substring(braceEnd + 1).trim();
                String[] parts = valueStr.split("\\s+");
                value = Double.parseDouble(parts[0]);
            } else {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) {
                    return null;
                }
                metricName = parts[0];
                value = Double.parseDouble(parts[1]);
            }

            return new ParsedMetric(metricName, tags, value);
        } catch (Exception e) {
            log.debug("[spring-watch: Prometheus指标解析失败 - line={}, error={}]", line, e.getMessage());
            return null;
        }
    }

    record ParsedMetric(String name, Map<String, String> tags, double value) {}
    record MonitorAppScraper(String appName, String endpoint, Integer metricsPort) {}
}