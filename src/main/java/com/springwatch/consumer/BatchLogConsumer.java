package com.springwatch.consumer;


import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import com.springwatch.model.event.LogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchLogConsumer {

    private final ObjectMapper objectMapper;
    private final WriteApi writeApi;
    private final WriteParameters logWriteParameters;

    @KafkaListener(
            topics = "monitor-logs",
            groupId = "spring-watch-log-writer",
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
                LogEvent event = objectMapper.readValue(message, LogEvent.class);
                points.add(toPoint(event));
            } catch (Exception e) {
                failed++;
                log.warn("[spring-watch: BatchLogConsumer 反序列化失败 - error={}, payload={}", 
                        e.getMessage(), message);
            }
        }
        if (!points.isEmpty()) {
            try {
                writeApi.writePoints(points, logWriteParameters);
                log.info("[spring-watch: BatchLogConsumer 写入InfluxDB - size={}, failed={}", 
                        points.size(), failed);
            } catch (Exception e) {
                log.error("[spring-watch: BatchLogConsumer 写InfluxDB失败 - size={}, error={}", 
                        points.size(), e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
    }

    private Point toPoint(LogEvent event) {
        Point point = Point.measurement("app_log")
                .addTag("appid", String.valueOf(event.getAppid()))
                .addTag("level", event.getLevel() != null ? event.getLevel() : "INFO")
                .addTag("logger", event.getLogger() != null ? event.getLogger() : "unknown")
                .addTag("threadName", event.getThreadName() != null ? event.getThreadName() : "unknown")
                .addField("message", event.getMessage() != null ? event.getMessage() : "")
                .time(event.getTimestamp() != null ? event.getTimestamp() : Instant.now(), WritePrecision.NS);
        if (event.getThrowable() != null) {
            point.addField("throwable", event.getThrowable());
        }
        if (event.getTraceId() != null) {
            point.addField("traceId", event.getTraceId());
        }
        return point;
    }
}
