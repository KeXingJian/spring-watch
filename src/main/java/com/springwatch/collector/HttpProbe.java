package com.springwatch.collector;

import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class HttpProbe {

    private final KafkaProducerBridge kafkaProducerBridge;

    public void probe(MonitorApp app) {
        String endpoint = app.getEndpoint();
        String probeUrl = endpoint + (endpoint.endsWith("/") ? "ping" : "/ping");
        long start = System.nanoTime();

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(probeUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int statusCode = conn.getResponseCode();
            long costMs = (System.nanoTime() - start) / 1_000_000;
            conn.disconnect();

            log.info("[spring-watch: HTTP探测 - app={}, url={}, status={}, cost={}ms]",
                    app.getAppName(), probeUrl, statusCode, costMs);

            MetricEvent event = MetricEvent.builder()
                    .appName(app.getAppName())
                    .metricName("http_probe")
                    .method("GET " + probeUrl)
                    .value((double) costMs)
                    .timestamp(Instant.now())
                    .tags(java.util.Map.of("status", String.valueOf(statusCode)))
                    .build();
            kafkaProducerBridge.sendMetric(event);

            if (statusCode < 400) {
                MetricEvent availabilityEvent = MetricEvent.builder()
                        .appName(app.getAppName())
                        .metricName("http_availability")
                        .method("GET " + probeUrl)
                        .value(1.0)
                        .timestamp(Instant.now())
                        .tags(java.util.Map.of("statusCode", String.valueOf(statusCode)))
                        .build();
                kafkaProducerBridge.sendMetric(availabilityEvent);

                sendHeartbeat(app);
            } else {
                MetricEvent availabilityEvent = MetricEvent.builder()
                        .appName(app.getAppName())
                        .metricName("http_availability")
                        .method("GET " + probeUrl)
                        .value(0.0)
                        .timestamp(Instant.now())
                        .tags(java.util.Map.of("statusCode", String.valueOf(statusCode)))
                        .build();
                kafkaProducerBridge.sendMetric(availabilityEvent);
            }

        } catch (Exception e) {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[spring-watch: HTTP探测失败 - app={}, url={}, error={}, cost={}ms]",
                    app.getAppName(), probeUrl, e.getMessage(), costMs);

            MetricEvent event = MetricEvent.builder()
                    .appName(app.getAppName())
                    .metricName("http_probe")
                    .method("GET " + probeUrl)
                    .value((double) costMs)
                    .timestamp(Instant.now())
                    .tags(java.util.Map.of("status", "error", "error", e.getMessage()))
                    .build();
            kafkaProducerBridge.sendMetric(event);
        }
    }

    private void sendHeartbeat(MonitorApp app) {
        String ip = parseHost(app.getEndpoint());
        HeartbeatEvent heartbeat = HeartbeatEvent.builder()
                .appName(app.getAppName())
                .ip(ip)
                .agentVersion("probe-v1")
                .timestamp(Instant.now())
                .build();
        kafkaProducerBridge.sendHeartbeat(heartbeat);
    }

    private String parseHost(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "unknown";
        }
        try {
            URI uri = URI.create(endpoint);
            return uri.getHost() != null ? uri.getHost() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}