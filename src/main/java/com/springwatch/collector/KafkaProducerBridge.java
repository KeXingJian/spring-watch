package com.springwatch.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.model.event.LogEvent;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaProducerBridge {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String TOPIC_METRICS = "monitor-metrics";
    private static final String TOPIC_LOGS = "monitor-logs";
    private static final String TOPIC_HEARTBEAT = "monitor-heartbeat";

    public void sendMetric(MetricEvent event) {
        send(TOPIC_METRICS, event.getAppName(), event);
    }

    public void sendLog(LogEvent event) {
        send(TOPIC_LOGS, event.getAppName(), event);
    }

    public void sendHeartbeat(HeartbeatEvent event) {
        send(TOPIC_HEARTBEAT, event.getAppName(), event);
    }

    private void send(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, key, json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("[spring-watch: Kafka发送失败 - topic={}, key={}, error={}]",
                                    topic, key, ex.getMessage());
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("[spring-watch: JSON序列化失败 - topic={}, key={}]", topic, key, e);
        }
    }
}