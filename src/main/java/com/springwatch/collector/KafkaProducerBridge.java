package com.springwatch.collector;


import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.model.event.LogEvent;
import com.springwatch.model.event.MetricEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaProducerBridge {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaFallbackQueue fallbackQueue;
    private final MeterRegistry meterRegistry;

    private static final String TOPIC_METRICS = "monitor-metrics";
    private static final String TOPIC_LOGS = "monitor-logs";
    private static final String TOPIC_HEARTBEAT = "monitor-heartbeat";

    /** 按 topic 缓存的发送/失败计数器,KafkaHealthMonitor 拉这些值写 InfluxDB。 */
    private final ConcurrentMap<String, Counter> sentCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> failedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> sendTimers = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        registerTopic(TOPIC_METRICS);
        registerTopic(TOPIC_LOGS);
        registerTopic(TOPIC_HEARTBEAT);
        log.info("[kxj: KafkaProducerBridge 启动 - 指标粒度: sent/failed.latency per topic]");
    }

    private void registerTopic(String topic) {
        sentCounters.computeIfAbsent(topic, t ->
                Counter.builder("spring.watch.kafka.producer.sent")
                        .tag("topic", t)
                        .description("KafkaProducer 发送成功累计条数")
                        .register(meterRegistry));
        failedCounters.computeIfAbsent(topic, t ->
                Counter.builder("spring.watch.kafka.producer.failed")
                        .tag("topic", t)
                        .description("KafkaProducer 发送失败累计条数(含转入降级队列)")
                        .register(meterRegistry));
        sendTimers.computeIfAbsent(topic, t ->
                Timer.builder("spring.watch.kafka.producer.send.latency")
                        .tag("topic", t)
                        .description("KafkaProducer send -> broker ack 时延")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry));
    }

    public void sendMetric(MetricEvent event) {
        send(TOPIC_METRICS, String.valueOf(event.getAppid()), event);
    }

    public void sendLog(LogEvent event) {
        send(TOPIC_LOGS, String.valueOf(event.getAppid()), event);
    }

    public void sendHeartbeat(HeartbeatEvent event) {
        send(TOPIC_HEARTBEAT, String.valueOf(event.getAppid()), event);
    }

    private void send(String topic, String key, Object event) {
        long startNs = System.nanoTime();
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("[kxj: JSON序列化失败 - topic={}, key={}]", topic, key, e);
            return;
        }
        try {
            kafkaTemplate.send(topic, key, json).whenComplete((result, ex) -> {
                long costNs = System.nanoTime() - startNs;
                Timer timer = sendTimers.get(topic);
                if (timer != null) timer.record(costNs, TimeUnit.NANOSECONDS);
                if (ex != null) {
                    Counter fc = failedCounters.get(topic);
                    if (fc != null) fc.increment();
                    log.warn("[kxj: Kafka发送失败, 转入降级队列 - topic={}, key={}, error={}]",
                            topic, key, ex.getMessage());
                    fallbackQueue.offer(topic, key, json);
                } else {
                    Counter sc = sentCounters.get(topic);
                    if (sc != null) sc.increment();
                    log.trace("[kxj: Kafka发送成功 - topic={}, key={}, partition={}, offset={}]",
                            topic, key,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        } catch (Exception e) {
            Counter fc = failedCounters.get(topic);
            if (fc != null) fc.increment();
            log.warn("[kxj: KafkaTemplate.send 抛异常, 直接入降级队列 - topic={}, key={}, error={}]",
                    topic, key, e.getMessage());
            fallbackQueue.offer(topic, key, json);
        }
    }

    /** 给 KafkaHealthMonitor 调用,取每个 topic 的累计计数,前端在 InfraPane 上算 rate。 */
    public double getSentCount(String topic) {
        Counter c = sentCounters.get(topic);
        return c == null ? 0d : c.count();
    }

    public double getFailedCount(String topic) {
        Counter c = failedCounters.get(topic);
        return c == null ? 0d : c.count();
    }
}
