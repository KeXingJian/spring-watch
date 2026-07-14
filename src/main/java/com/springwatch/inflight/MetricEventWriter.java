package com.springwatch.inflight;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricEventWriter {

    @Qualifier("metricsWriteApi")
    private final WriteApi writeApi;
    private final WriteParameters metricsWriteParameters;

    public int write(List<Object> events) {
        if (events == null || events.isEmpty()) return 0;
        List<Point> points = new java.util.ArrayList<>(events.size());
        for (Object e : events) {
            if (!(e instanceof MetricEvent event)) continue;
            points.add(toPoint(event));
        }
        if (points.isEmpty()) return 0;
        writeApi.writePoints(points, metricsWriteParameters);
        return points.size();
    }

    private Point toPoint(MetricEvent event) {
        Point point = Point.measurement("springboot_metrics")
            .addTag("appid", String.valueOf(event.getAppid()))
            .addTag("metric", event.getMetricName() != null ? event.getMetricName() : "unknown")
            .addTag("method", event.getMethod() != null ? event.getMethod() : "unknown")
            .addField("value", event.getValue() != null ? event.getValue() : 0.0)
            .time(event.getTimestamp(), WritePrecision.NS);
        if (event.getCount() != null) point.addField("count", event.getCount());
        if (event.getTags() != null) event.getTags().forEach(point::addTag);
        return point;
    }
}
