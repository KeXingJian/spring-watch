package com.springwatch.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class DlqMonitorConsumer {

    @KafkaListener(
            topics = {"monitor-metrics.DLQ", "monitor-logs.DLQ", "monitor-heartbeat.DLQ"},
            groupId = "spring-watch-dlq-monitor",
            containerFactory = "batchFactory"
    )
    public void onDlq(List<String> messages,
                      @Header(
                              name = org.springframework.kafka.support.KafkaHeaders.RECEIVED_TOPIC,
                              required = false) List<String> topics) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int total = messages.size();
        for (int i = 0; i < messages.size(); i++) {
            String topic = topics != null && i < topics.size() ? topics.get(i) : "unknown";
            String payload = messages.get(i);
            log.error("[spring-watch: DLQ消息 - topic={}, payload={}]", topic, payload);
        }
        log.warn("[spring-watch: DLQ批处理 - count={}, topics={}]", 
                total, topics);
    }
}
