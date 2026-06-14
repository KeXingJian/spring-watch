package com.springwatch.consumer;

import com.springwatch.alerter.AsyncAlertExecutor;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchAlertConsumer {

    private final ObjectMapper objectMapper;
    private final AsyncAlertExecutor executor;

    private final AtomicLong totalNoAppid = new AtomicLong(0);

    @KafkaListener(
            topics = "monitor-metrics",
            groupId = "spring-watch-alert-evaluator",
            containerFactory = "batchFactory"
    )
    public void onBatch(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int submitted = 0;
        int failed = 0;
        int noAppid = 0;
        String sampleNoAppid = null;
        for (String message : messages) {
            try {
                MetricEvent event = objectMapper.readValue(message, MetricEvent.class);
                if (event.getAppid() == null) {
                    noAppid++;
                    if (sampleNoAppid == null) {
                        sampleNoAppid = message.length() > 200 ? message.substring(0, 200) : message;
                    }
                    continue;
                }
                executor.submit(event);
                submitted++;
            } catch (Exception e) {
                failed++;
                if (failed <= 3) {
                    log.warn("[Alerter] 反序列化失败 - error={}, payload={}", e.getMessage(), message);
                }
            }
        }
        if (noAppid > 0) {
            long total = totalNoAppid.addAndGet(noAppid);
            if (total % 500 == 0 || total < 500) {
                log.warn("[Alerter] 事件无appid, 跳过评估 - 本批noAppid={}, 累计={}, sample={}",
                        noAppid, total, sampleNoAppid);
            }
        }
        if (submitted > 0 || failed > 0) {
            log.info("[Alerter] 批次处理完成 - total={}, submitted={}, failed={}, noAppid={}",
                    messages.size(), submitted, failed, noAppid);
        }
    }
}
