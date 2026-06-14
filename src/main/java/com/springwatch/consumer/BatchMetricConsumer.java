package com.springwatch.consumer;


import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchMetricConsumer {

    private final WriteApi writeApi;
    private final ObjectMapper objectMapper;
    private final WriteParameters metricsWriteParameters;

    @KafkaListener(
            topics = "monitor-metrics",
            groupId = "spring-watch-metric-writer",
            containerFactory = "batchFactory"
    )
    public void onBatch(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<Point> points = new ArrayList<>(messages.size());
        int failed = 0;
        for (String message : messages) {
            try {
                MetricEvent event = objectMapper.readValue(message, MetricEvent.class);
                points.add(toPoint(event));
            } catch (Exception e) {
                failed++;
                log.warn("[spring-watch: BatchMetricConsumer 反序列化失败 - error={}, payload={}", 
                        e.getMessage(), message);
            }
        }
        if (!points.isEmpty()) {
            try {
                writeApi.writePoints(points, metricsWriteParameters);
                log.info("[spring-watch: BatchMetricConsumer 写入InfluxDB - size={}, failed={}", 
                        points.size(), failed);
            } catch (Exception e) {
                log.error("[spring-watch: BatchMetricConsumer 写InfluxDB失败 - size={}, error={}", 
                        points.size(), e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
    }

    private Point toPoint(MetricEvent event) {
        Point point = Point.measurement("springboot_metrics")
                .addTag("appid", String.valueOf(event.getAppid()))
                .addTag("metric", event.getMetricName() != null ? event.getMetricName() : "unknown")
                .addTag("method", event.getMethod() != null ? event.getMethod() : "unknown")
                .addField("value", event.getValue() != null ? event.getValue() : 0.0)
                .time(event.getTimestamp(), WritePrecision.NS);
        if (event.getCount() != null) {
            point.addField("count", event.getCount());
        }
        if (event.getTags() != null) {
            event.getTags().forEach(point::addTag);
        }
        return point;
    }
}
