package com.springwatch.ai.diagnosis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.springwatch.model.event.AlertTriggeredEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 告警诊断触发器 - 订阅 AlertTriggeredEvent(事务提交后),
 * 异步执行诊断,不阻塞告警通知主链路。
 * 限流:同一 app 5 分钟内不重复诊断(防告警风暴时 LLM 调用爆炸)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertDiagnosisTrigger {

    private final DiagnosisReportService diagnosisReportService;

    @Value("${spring-watch.alert.diagnosis.enabled:true}")
    private boolean diagnosisEnabled;

    @Value("${spring-watch.alert.diagnosis.cooldown-minutes:5}")
    private long cooldownMinutes;

    @Value("${spring-watch.alert.diagnosis.max-concurrent:2}")
    private int maxConcurrent;

    private ExecutorService executor;
    private Semaphore semaphore;
    private Cache<Long, Boolean> cooldownCache;

    @PostConstruct
    void init() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.semaphore = new Semaphore(maxConcurrent);
        this.cooldownCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(cooldownMinutes))
                .build();
        log.info("[kxj: AI告警诊断触发器启动 - enabled={}, cooldown={}min, maxConcurrent={}]",
                diagnosisEnabled, cooldownMinutes, maxConcurrent);
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("[kxj: AI告警诊断触发器线程池关闭");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlertTriggered(AlertTriggeredEvent event) {
        if (!diagnosisEnabled || event == null || event.getAppid() == null) {
            return;
        }
        if (cooldownCache.getIfPresent(event.getAppid()) != null) {
            log.debug("[kxj: AI告警诊断限流跳过 - appid={}, historyId={}]", event.getAppid(), event.getHistoryId());
            return;
        }
        cooldownCache.put(event.getAppid(), Boolean.TRUE);
        try {
            semaphore.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        executor.execute(() -> {
            try {
                diagnosisReportService.diagnose(event);
            } catch (Throwable t) {
                log.error("[kxj: AI告警诊断异常 - appid={}, historyId={}, error={}]",
                        event.getAppid(), event.getHistoryId(), t.getMessage(), t);
            } finally {
                semaphore.release();
            }
        });
    }
}