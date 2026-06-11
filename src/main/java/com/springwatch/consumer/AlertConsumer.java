package com.springwatch.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springwatch.alerter.AlertEvaluator;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertConsumer {

    private final ObjectMapper objectMapper;
    private final AlertEvaluator alertEvaluator;

    @KafkaListener(topics = "monitor-metrics", groupId = "spring-watch-alert-consumer")
    public void onMetric(String message) {
        try {
            MetricEvent event = objectMapper.readValue(message, MetricEvent.class);
            alertEvaluator.evaluate(event);
        } catch (Exception e) {
            log.error("[spring-watch: AlertConsumer 处理失败 - error={}]", e.getMessage(), e);
        }
    }
}