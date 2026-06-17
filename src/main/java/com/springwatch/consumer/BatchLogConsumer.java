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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
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
    private final MeterRegistry meterRegistry;

    @Value("${spring-watch.log.env:dev}")
    private String defaultEnv;

    private Counter receivedCounter;
    private Counter keptCounter;
    private Counter dedupedCounter;
    private Counter parseFailCounter;
    private Counter writeFailCounter;
    private Counter alertCandidateCounter;
    private Timer writeTimer;

    @PostConstruct
    void initMetrics() {
        this.receivedCounter = Counter.builder("spring.watch.consumer.log.received")
                .description("日志消费条数(Kafka 接收)")
                .register(meterRegistry);
        this.keptCounter = Counter.builder("spring.watch.consumer.log.kept")
                .description("日志实际写入 InfluxDB 条数")
                .register(meterRegistry);
        this.dedupedCounter = Counter.builder("spring.watch.consumer.log.deduped")
                .description("日志去重丢弃条数")
                .register(meterRegistry);
        this.parseFailCounter = Counter.builder("spring.watch.consumer.log.parse_fail")
                .description("日志反序列化/解析失败条数")
                .register(meterRegistry);
        this.writeFailCounter = Counter.builder("spring.watch.consumer.log.write_fail")
                .description("日志写 InfluxDB 失败次数(批次级)")
                .register(meterRegistry);
        this.alertCandidateCounter = Counter.builder("spring.watch.consumer.log.alert_candidate")
                .description("日志提交告警评估次数")
                .register(meterRegistry);
        this.writeTimer = Timer.builder("spring.watch.consumer.log.write")
                .description("日志批写 InfluxDB 耗时")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = "monitor-logs",
            groupId = "spring-watch-log-writer",
            containerFactory = "batchFactory"
    )
    public void onBatch(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        receivedCounter.increment(messages.size());
        List<Point> points = new ArrayList<>(messages.size());
        List<LogEvent> alertCandidates = new ArrayList<>(messages.size());
        int failed = 0;
        int deduped = 0;
        for (String message : messages) {
            try {
                LogEvent event = objectMapper.readValue(message, LogEvent.class);
                if (event.getAppid() == null) {
                    failed++;
                    parseFailCounter.increment();
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
                    dedupedCounter.increment();
                    continue;
                }
                points.add(toPoint(event));
                alertCandidates.add(event);
            } catch (Exception e) {
                failed++;
                parseFailCounter.increment();
                log.warn("[spring-watch: BatchLogConsumer 反序列化失败 - error={}, payload={}]",
                        e.getMessage(), message);
            }
        }
        if (!points.isEmpty()) {
            try {
                long start = System.nanoTime();
                writeApi.writePoints(points, logWriteParameters);
                writeTimer.record(Duration.ofNanos(System.nanoTime() - start));
                keptCounter.increment(points.size());
                log.info("[spring-watch: BatchLogConsumer 写入InfluxDB - total={}, kept={}, deduped={}, failed={}]",
                        messages.size(), points.size(), deduped, failed);
            } catch (Exception e) {
                writeFailCounter.increment();
                log.error("[spring-watch: BatchLogConsumer 写InfluxDB失败 - size={}, error={}], 本批丢弃,不重投,避免一条坏数据死循环]",
                        points.size(), e.getMessage());
            }
        } else if (deduped > 0 || failed > 0) {
            log.info("[spring-watch: BatchLogConsumer 全部丢弃 - total={}, deduped={}, failed={}]",
                    messages.size(), deduped, failed);
        }
        for (LogEvent candidate : alertCandidates) {
            alertExecutor.submit(candidate);
            alertCandidateCounter.increment();
        }
    }

    private Point toPoint(LogEvent event) {
        Point point = Point.measurement("app_log")
                .addTag("appid", String.valueOf(event.getAppid()))
                .addTag("level", event.getLevel() != null ? event.getLevel() : "INFO")
                .addTag("logger", event.getLogger() != null ? event.getLogger() : "unknown")
                .addTag("threadName", event.getThreadName() != null ? event.getThreadName() : "unknown")
                .addField("message", event.getMessage() != null ? event.getMessage() : "")
                .addField("fingerprint", event.getFingerprint() != null ? event.getFingerprint() : "unknown")
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
