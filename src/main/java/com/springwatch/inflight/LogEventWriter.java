package com.springwatch.inflight;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import com.springwatch.model.event.LogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogEventWriter {

    @Qualifier("logsWriteApi")
    private final WriteApi writeApi;
    private final WriteParameters logWriteParameters;

    public int write(List<Object> events) {
        if (events == null || events.isEmpty()) return 0;
        List<Point> points = new ArrayList<>(events.size());
        for (Object e : events) {
            if (!(e instanceof LogEvent event)) continue;
            points.add(toPoint(event));
        }
        if (points.isEmpty()) return 0;
        writeApi.writePoints(points, logWriteParameters);
        return points.size();
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
