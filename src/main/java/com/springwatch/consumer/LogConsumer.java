package com.springwatch.consumer;


import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import com.springwatch.model.event.LogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogConsumer {

    private final ObjectMapper objectMapper;
    private final WriteApi writeApi;
    private final WriteParameters logWriteParameters;


    @KafkaListener(topics = "monitor-logs", groupId = "spring-watch-log-consumer")
    public void onLog(String message) {
        try {
            LogEvent event = objectMapper.readValue(message, LogEvent.class);
            log.trace("[spring-watch: LogConsumer 收到日志 - appid={}, level={}, logger={}]",
                    event.getAppid(), event.getLevel(), event.getLogger());

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

            writeApi.writePoint(point, logWriteParameters);
            log.trace("[spring-watch: LogConsumer 写入InfluxDB完成 - appid={}, level={}]",
                    event.getAppid(), event.getLevel());
        } catch (Exception e) {
            log.error("[spring-watch: LogConsumer 处理失败 - error={}]", e.getMessage(), e);
        }
    }
}
