package com.springwatch.consumer;


import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import com.springwatch.alerter.AsyncAlertExecutor;
import com.springwatch.ingest.LogDedupService;
import com.springwatch.ingest.LogFingerprinter;
import com.springwatch.ingest.LogParser;
import com.springwatch.ingest.LogSanitizer;
import com.springwatch.model.event.LogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final LogParser logParser;
    private final LogFingerprinter fingerprinter;
    private final LogSanitizer sanitizer;
    private final LogDedupService dedupService;
    private final AsyncAlertExecutor alertExecutor;

    @Value("${spring-watch.log.env:dev}")
    private String defaultEnv;

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
        List<LogEvent> alertCandidates = new ArrayList<>(messages.size());
        int failed = 0;
        int deduped = 0;
        for (String message : messages) {
            try {
                LogEvent event = objectMapper.readValue(message, LogEvent.class);
                if (event.getAppid() == null) {
                    failed++;
                    log.warn("[spring-watch: BatchLogConsumer 跳过无appid日志 - payload={}]", message);
                    continue;
                }



                logParser.enrich(event, event.getHost(), defaultEnv);
                sanitizer.mask(event);
                String fp = fingerprinter.fingerprint(event);
                event.setFingerprint(fp);
                if (event.getPattern() == null) {
                    event.setPattern(fingerprinter.patternName(event));
                }

                if (!dedupService.shouldKeep(event.getAppid(), fp)) {
                    deduped++;
                    continue;
                }
                points.add(toPoint(event));
                alertCandidates.add(event);
            } catch (Exception e) {
                failed++;
                log.warn("[spring-watch: BatchLogConsumer 反序列化失败 - error={}, payload={}]",
                        e.getMessage(), message);
            }
        }
        if (!points.isEmpty()) {
            try {
                writeApi.writePoints(points, logWriteParameters);
                log.info("[spring-watch: BatchLogConsumer 写入InfluxDB - total={}, kept={}, deduped={}, failed={}]",
                        messages.size(), points.size(), deduped, failed);
            } catch (Exception e) {
                log.error("[spring-watch: BatchLogConsumer 写InfluxDB失败 - size={}, error={}]",
                        points.size(), e.getMessage(), e);
                throw new RuntimeException(e);
            }
        } else if (deduped > 0 || failed > 0) {
            log.info("[spring-watch: BatchLogConsumer 全部丢弃 - total={}, deduped={}, failed={}]",
                    messages.size(), deduped, failed);
        }
        for (LogEvent candidate : alertCandidates) {
            alertExecutor.submit(candidate);
        }
    }

    private Point toPoint(LogEvent event) {
        Point point = Point.measurement("app_log")
                .addTag("appid", String.valueOf(event.getAppid()))
                .addTag("level", event.getLevel() != null ? event.getLevel() : "INFO")
                .addTag("logger", event.getLogger() != null ? event.getLogger() : "unknown")
                .addTag("threadName", event.getThreadName() != null ? event.getThreadName() : "unknown")
                .addTag("fingerprint", event.getFingerprint() != null ? event.getFingerprint() : "unknown")
                .addField("message", event.getMessage() != null ? event.getMessage() : "")
                .time(event.getTimestamp() != null ? event.getTimestamp() : Instant.now(), WritePrecision.NS);
        if (event.getThrowable() != null) {
            point.addField("throwable", event.getThrowable());
        }
        if (event.getTraceId() != null) {
            point.addField("traceId", event.getTraceId());
        }
        if (event.getHost() != null) {
            point.addField("host", event.getHost());
        }
        if (event.getService() != null) {
            point.addField("service", event.getService());
        }
        if (event.getMethod() != null) {
            point.addField("method", event.getMethod());
        }
        if (event.getEnv() != null) {
            point.addField("env", event.getEnv());
        }
        if (event.getPattern() != null) {
            point.addField("pattern", event.getPattern());
        }
        return point;
    }
}
