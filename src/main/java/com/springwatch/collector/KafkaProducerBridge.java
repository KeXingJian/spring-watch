package com.springwatch.collector;


import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.model.event.LogEvent;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaProducerBridge {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaFallbackQueue fallbackQueue;

    private static final String TOPIC_METRICS = "monitor-metrics";
    private static final String TOPIC_LOGS = "monitor-logs";
    private static final String TOPIC_HEARTBEAT = "monitor-heartbeat";

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
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("[spring-watch: JSON序列化失败 - topic={}, key={}]", topic, key, e);
            return;
        }
        try {
            kafkaTemplate.send(topic, key, json).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("[spring-watch: Kafka发送失败, 转入降级队列 - topic={}, key={}, error={}]",
                            topic, key, ex.getMessage());
                    fallbackQueue.offer(topic, key, json);
                } else {
                    log.trace("[spring-watch: Kafka发送成功 - topic={}, key={}, partition={}, offset={}]",
                            topic, key,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        } catch (Exception e) {
            log.warn("[spring-watch: KafkaTemplate.send 抛异常, 直接入降级队列 - topic={}, key={}, error={}]",
                    topic, key, e.getMessage());
            fallbackQueue.offer(topic, key, json);
        }
    }
}
