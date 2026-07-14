package com.springwatch.inflight;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import com.springwatch.model.event.MetricEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricEventWriter {

    @Qualifier("metricsWriteApi")
    private final WriteApi writeApi;
    private final WriteParameters metricsWriteParameters;
    private final MeterRegistry meterRegistry;

    private Counter receivedCounter;
    private Counter parseFailCounter;
    private Counter writeFailCounter;
    private Timer writeTimer;

    @jakarta.annotation.PostConstruct
    void init() {
        this.receivedCounter = Counter.builder("spring.watch.consumer.metric.received")
                .description("MetricEventWriter 收到 MetricEvent 条数")
                .register(meterRegistry);
        this.parseFailCounter = Counter.builder("spring.watch.consumer.metric.parse_fail")
                .description("MetricEventWriter 类型转换失败条数")
                .register(meterRegistry);
        this.writeFailCounter = Counter.builder("spring.watch.consumer.metric.write_fail")
                .description("MetricEventWriter 写 InfluxDB 失败条数")
                .register(meterRegistry);
        this.writeTimer = Timer.builder("spring.watch.consumer.metric.write")
                .description("MetricEventWriter 写 InfluxDB 耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public int write(List<Object> events) {
        if (events == null || events.isEmpty()) return 0;
        List<Point> points = new java.util.ArrayList<>(events.size());
        int parseFails = 0;
        for (Object e : events) {
            receivedCounter.increment();
            if (!(e instanceof MetricEvent event)) {
                parseFailCounter.increment();
                parseFails++;
                continue;
            }
            try {
                points.add(toPoint(event));
            } catch (Throwable t) {
                parseFailCounter.increment();
                parseFails++;
            }
        }
        if (points.isEmpty()) return 0;
        long start = System.nanoTime();
        try {
            writeApi.writePoints(points, metricsWriteParameters);
            writeTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return points.size();
        } catch (Throwable t) {
            writeFailCounter.increment();
            writeTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            log.warn("[kxj: MetricEventWriter 写 InfluxDB 失败 - size={}, error={}]",
                    points.size(), t.getMessage());
            return 0;
        }
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
