package com.springwatch.consumer;


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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchMetricConsumer {

    @Qualifier("metricsWriteApi")
    private final WriteApi writeApi;
    private final ObjectMapper objectMapper;
    private final WriteParameters metricsWriteParameters;
    private final MeterRegistry meterRegistry;

    private Counter receivedCounter;
    private Counter keptCounter;
    private Counter parseFailCounter;
    private Counter writeFailCounter;
    private Timer writeTimer;

    @jakarta.annotation.PostConstruct
    void initMetrics() {
        this.receivedCounter = Counter.builder("spring.watch.consumer.metric.received")
                .description("指标消费条数")
                .register(meterRegistry);
        this.keptCounter = Counter.builder("spring.watch.consumer.metric.kept")
                .description("指标实际写入 InfluxDB 条数")
                .register(meterRegistry);
        this.parseFailCounter = Counter.builder("spring.watch.consumer.metric.parse_fail")
                .description("指标反序列化失败条数")
                .register(meterRegistry);
        this.writeFailCounter = Counter.builder("spring.watch.consumer.metric.write_fail")
                .description("指标写 InfluxDB 失败次数")
                .register(meterRegistry);
        this.writeTimer = Timer.builder("spring.watch.consumer.metric.write")
                .description("指标批写 InfluxDB 耗时")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = "monitor-metrics",
            groupId = "spring-watch-metric-writer",
            containerFactory = "batchFactory"
    )
    public void onBatch(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        receivedCounter.increment(messages.size());
        List<Point> points = new ArrayList<>(messages.size());
        int failed = 0;
        for (String message : messages) {
            try {
                MetricEvent event = objectMapper.readValue(message, MetricEvent.class);
                points.add(toPoint(event));
            } catch (Exception e) {
                failed++;
                parseFailCounter.increment();
                log.warn("[spring-watch: BatchMetricConsumer 反序列化失败 - error={}, payload={}",
                        e.getMessage(), message);
            }
        }
        if (!points.isEmpty()) {
            try {
                long start = System.nanoTime();
                writeApi.writePoints(points, metricsWriteParameters);
                writeTimer.record(Duration.ofNanos(System.nanoTime() - start));
                keptCounter.increment(points.size());
                log.info("[spring-watch: BatchMetricConsumer 写入InfluxDB - size={}, failed={}",
                        points.size(), failed);
            } catch (Exception e) {
                writeFailCounter.increment();
                log.error("[spring-watch: BatchMetricConsumer 写InfluxDB失败 - size={}, error={}], 本批丢弃,不重投]",
                        points.size(), e.getMessage());
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
