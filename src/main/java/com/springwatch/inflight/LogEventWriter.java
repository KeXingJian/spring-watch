package com.springwatch.inflight;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import com.springwatch.ingest.LogDedupService;
import com.springwatch.ingest.LogFingerprinter;
import com.springwatch.model.event.LogEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogEventWriter {

    @Qualifier("logsWriteApi")
    private final WriteApi writeApi;
    private final WriteParameters logWriteParameters;
    private final MeterRegistry meterRegistry;
    private final LogDedupService logDedupService;
    private final LogFingerprinter logFingerprinter;

    private Counter receivedCounter;
    private Counter keptCounter;
    private Counter parseFailCounter;
    private Counter writeFailCounter;
    private Timer writeTimer;

    @jakarta.annotation.PostConstruct
    void init() {
        this.receivedCounter = Counter.builder("spring.watch.consumer.log.received")
                .description("LogEventWriter 收到 LogEvent 条数")
                .register(meterRegistry);
        this.keptCounter = Counter.builder("spring.watch.consumer.log.kept")
                .description("LogEventWriter 成功解析并入库条数")
                .register(meterRegistry);
        this.parseFailCounter = Counter.builder("spring.watch.consumer.log.parse_fail")
                .description("LogEventWriter 类型转换失败条数")
                .register(meterRegistry);
        this.writeFailCounter = Counter.builder("spring.watch.consumer.log.write_fail")
                .description("LogEventWriter 写 InfluxDB 失败条数")
                .register(meterRegistry);
        this.writeTimer = Timer.builder("spring.watch.consumer.log.write")
                .description("LogEventWriter 写 InfluxDB 耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public int write(List<Object> events) {
        if (events == null || events.isEmpty()) return 0;
        List<Point> points = new ArrayList<>(events.size());
        for (Object e : events) {
            receivedCounter.increment();
            if (!(e instanceof LogEvent event)) {
                parseFailCounter.increment();
                continue;
            }
            // v2.0 替代旧 BatchLogConsumer:mock-test 等老 Agent 不下发 fingerprint,
            // 写入前先调 LogFingerprinter 按 level/logger/message 归一化后 SHA-1 补算,
            // 否则 LogDedupService.shouldKeep 看到 fingerprint=null 会 early return 跳过计数
            if (event.getFingerprint() == null || event.getFingerprint().isEmpty()) {
                try {
                    event.setFingerprint(logFingerprinter.fingerprint(event));
                } catch (Throwable t) {
                    log.warn("[kxj: LogEventWriter fingerprint 计算失败 - appid={}, error={}]",
                            event.getAppid(), t.getMessage());
                }
            }
            // v2.0 替代旧 BatchLogConsumer:每条 LogEvent 先过服务端 dedup,丢弃的不入库
            // keep/drop/flush 速率由 LogDedupService 内部 counter 维护,这里只调用
            try {
                if (event.getAppid() != null && !logDedupService.shouldKeep(event.getAppid(), event.getFingerprint())) {
                    continue;
                }
            } catch (Throwable t) {
                log.warn("[kxj: LogEventWriter dedup 异常,放行 - appid={}, error={}]",
                        event.getAppid(), t.getMessage());
            }
            try {
                points.add(toPoint(event));
            } catch (Throwable t) {
                parseFailCounter.increment();
            }
        }
        if (points.isEmpty()) return 0;
        long start = System.nanoTime();
        try {
            writeApi.writePoints(points, logWriteParameters);
            writeTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            keptCounter.increment(points.size());
            return points.size();
        } catch (Throwable t) {
            writeFailCounter.increment();
            writeTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            log.warn("[kxj: LogEventWriter 写 InfluxDB 失败 - size={}, error={}]",
                    points.size(), t.getMessage());
            return 0;
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
        if (event.getThrowable() != null) point.addField("throwable", event.getThrowable());
        if (event.getTraceId() != null) point.addField("traceId", event.getTraceId());
        if (event.getHost() != null) point.addField("host", event.getHost());
        if (event.getService() != null) point.addField("service", event.getService());
        if (event.getMethod() != null) point.addField("method", event.getMethod());
        if (event.getEnv() != null) point.addField("env", event.getEnv());
        if (event.getPattern() != null) point.addField("pattern", event.getPattern());
        return point;
    }
}
