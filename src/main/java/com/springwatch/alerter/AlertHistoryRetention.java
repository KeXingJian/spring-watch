package com.springwatch.alerter;

import com.springwatch.repository.AlertHistoryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警历史保留策略 - P0-5 / M4-1
 * 每日凌晨清理超过 retentionDays 的历史，避免 alert_history 表无界增长。
 * 同时对外暴露两条 Micrometer 指标:
 *   - spring.watch.alert.history.total_rows(Gauge, 走 repository.count())
 *   - spring.watch.alert.history.purged    (Counter, 每次 purge 累加)
 * 用于自监控卡 + Prometheus 告警,验证清理策略按预期执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertHistoryRetention {

    private final AlertHistoryRepository repository;
    private final MeterRegistry meterRegistry;

    @Value("${spring-watch.alert.history.retention-days:90}")
    private int retentionDays;

    private Counter purgedCounter;
    private final AtomicLong lastPurgedRows = new AtomicLong(0L);
    private volatile Instant lastPurgedAt = Instant.EPOCH;

    @PostConstruct
    void initMetrics() {
        Gauge.builder("spring.watch.alert.history.total_rows", repository, AlertHistoryRepository::count)
                .description("alert_history 表当前总行数")
                .register(meterRegistry);
        purgedCounter = Counter.builder("spring.watch.alert.history.purged")
                .description("历史清理累计删除行数")
                .register(meterRegistry);
        Gauge.builder("spring.watch.alert.history.purged.last_rows", lastPurgedRows, AtomicLong::doubleValue)
                .description("最近一次清理删除行数")
                .register(meterRegistry);
        Gauge.builder("spring.watch.alert.history.purged.last_at_epoch", this, r -> (double) r.lastPurgedAt.toEpochMilli())
                .description("最近一次清理的 epoch ms,前端转 ISO")
                .register(meterRegistry);
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int n = repository.deleteByCreatedAtBefore(cutoff);
        if (n > 0) {
            purgedCounter.increment(n);
        }
        lastPurgedRows.set(n);
        lastPurgedAt = Instant.now();
        log.info("[Alerter] 告警历史保留清理 - retentionDays={}, cutoff={}, purged={}",
                retentionDays, cutoff, n);
    }
}
