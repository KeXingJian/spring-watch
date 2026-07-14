package com.springwatch.consumer;

import com.springwatch.alerter.AsyncAlertExecutor;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v2.0:从 InflightQueue 接收指标 batch,做告警评估(替代 v1.6 @KafkaListener)
 * 业务流程保持不变:遍历 events → executor.submit(MetricEvent)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchAlertConsumer {

    private final AsyncAlertExecutor executor;

    private final AtomicLong totalNoAppid = new AtomicLong(0);

    public void evaluate(List<Object> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        int submitted = 0;
        int failed = 0;
        int noAppid = 0;
        Object sampleNoAppid = null;
        for (Object e : events) {
            if (!(e instanceof MetricEvent event)) continue;
            if (event.getAppid() == null) {
                noAppid++;
                if (sampleNoAppid == null) {
                    sampleNoAppid = event;
                }
                continue;
            }
            try {
                executor.submit(event);
                submitted++;
            } catch (Exception ex) {
                failed++;
                if (failed <= 3) {
                    log.warn("[Alerter] submit 失败 - error={}", ex.getMessage());
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
                    events.size(), submitted, failed, noAppid);
        }
    }
}
