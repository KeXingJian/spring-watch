package com.springwatch.alerter;

import com.springwatch.repository.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 告警历史保留策略 - P0-5
 * 每日凌晨清理超过 retentionDays 的历史，避免 alert_history 表无界增长。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertHistoryRetention {

    private final AlertHistoryRepository repository;

    @Value("${spring-watch.alert.history.retention-days:90}")
    private int retentionDays;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int n = repository.deleteByCreatedAtBefore(cutoff);
        log.info("[Alerter] 告警历史保留清理 - retentionDays={}, cutoff={}, purged={}",
                retentionDays, cutoff, n);
    }
}
