package com.springwatch.collector;

import com.springwatch.model.entity.MonitorApp;
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

            MetricEvent availabilityEvent = MetricEvent.builder()
                    .appName(app.getAppName())
                    .metricName("http_availability")
                    .method("GET " + probeUrl)
                    .value(statusCode < 400 ? 1.0 : 0.0)
                    .timestamp(Instant.now())
                    .tags(java.util.Map.of("statusCode", String.valueOf(statusCode)))
                    .build();
            kafkaProducerBridge.sendMetric(availabilityEvent);

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
}