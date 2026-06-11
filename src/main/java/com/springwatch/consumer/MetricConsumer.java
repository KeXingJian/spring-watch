package com.springwatch.consumer;


import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricConsumer {

    private final WriteApi writeApi;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "monitor-metrics", groupId = "spring-watch-metric-consumer")
    public void onMetric(String message) {
        try {
            MetricEvent event = objectMapper.readValue(message, MetricEvent.class);
            log.debug("[spring-watch: MetricConsumer 收到指标 - app={}, metric={}, value={}]",
                    event.getAppName(), event.getMetricName(), event.getValue());

            Point point = Point.measurement("springboot_metrics")
                    .addTag("app", event.getAppName())
                    .addTag("metric", event.getMetricName())
                    .addTag("method", event.getMethod() != null ? event.getMethod() : "unknown")
                    .addField("value", event.getValue())
                    .time(event.getTimestamp(), WritePrecision.NS);

            if (event.getCount() != null) {
                point.addField("count", event.getCount());
            }
            if (event.getTags() != null) {
                event.getTags().forEach(point::addTag);
            }

            writeApi.writePoint(point);
            log.debug("[spring-watch: MetricConsumer 写入InfluxDB完成 - app={}, metric={}, value={}]",
                    event.getAppName(), event.getMetricName(), event.getValue());
        } catch (Exception e) {
            log.error("[spring-watch: MetricConsumer 处理失败 - error={}]", e.getMessage(), e);
        }
    }
}