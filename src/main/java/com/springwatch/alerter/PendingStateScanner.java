package com.springwatch.alerter;

import com.springwatch.model.entity.AlertRule;
import com.springwatch.repository.AlertRuleRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingStateScanner {

    private final AlertStateStore stateStore;
    private final AlertRuleRepository ruleRepository;
    private final AsyncAlertExecutor alertExecutor;

    @Value("${spring-watch.alert.scan.enabled:true}")
    private boolean enabled;

    @Value("${spring-watch.alert.scan.batch-size:200}")
    private long batchSize;

    private ExecutorService scanExecutor;

    @PostConstruct
    void init() {
        ThreadFactory tf = Thread.ofVirtual().name("pending-scanner-", 0).factory();
        this.scanExecutor = Executors.newSingleThreadExecutor(tf);
        log.info("[Alerter] PENDING扫描器初始化 - enabled={}, batchSize={}, threadType=virtual", enabled, batchSize);
    }

    @PreDestroy
    void shutdown() {
        if (scanExecutor != null) {
            scanExecutor.shutdown();
            try {
                if (!scanExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    scanExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                scanExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * kxj: 周期扫PENDING状态-真实连续判断 [借鉴 HertzBeat PeriodicAlertRuleScheduler]
     * 频率默认5秒,够细不至于漏判,又不会给Redis太大压力
     */
    @Scheduled(fixedDelayString = "${spring-watch.alert.scan.interval-ms:5000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        if (scanExecutor == null) {
            log.warn("[Alerter] 扫描线程池未初始化, 同步执行");
            doScan();
            return;
        }
        try {
            scanExecutor.execute(this::doScan);
        } catch (Exception e) {
            log.warn("[Alerter] 提交扫描任务失败, 同步执行 - error={}", e.getMessage());
            doScan();
        }
    }

    private void doScan() {
        long start = System.nanoTime();
        try {
            List<AlertStateStore.PendingEntry> entries = stateStore.scanPending(batchSize);
            if (entries.isEmpty()) {
                return;
            }
            log.debug("[Alerter] 扫描器发现PENDING - count={}", entries.size());
            int fired = 0;
            int skipped = 0;
            Instant now = Instant.now();
            for (AlertStateStore.PendingEntry entry : entries) {
                try {
                    Optional<AlertRule> ruleOpt = ruleRepository.findById(entry.ruleId());
                    if (ruleOpt.isEmpty()) {
                        log.debug("[Alerter] 扫描器跳过已删除规则 - ruleId={}, appid={}", entry.ruleId(), entry.appid());
                        stateStore.clear(entry.ruleId(), entry.appid());
                        skipped++;
                        continue;
                    }
                    AlertRule rule = ruleOpt.get();
                    if (!"enabled".equalsIgnoreCase(rule.getStatus())) {
                        log.debug("[Alerter] 扫描器跳过已禁用规则 - ruleId={}, appid={}, status={}",
                                entry.ruleId(), entry.appid(), rule.getStatus());
                        stateStore.clear(entry.ruleId(), entry.appid());
                        skipped++;
                        continue;
                    }
                    alertExecutor.submitFromScanner(rule, entry.appid(), entry.firstBreachAt(), entry.triggerCount(), now);
                    fired++;
                } catch (Exception e) {
                    log.warn("[Alerter] 扫描器处理单条失败 - ruleId={}, appid={}, error={}",
                            entry.ruleId(), entry.appid(), e.getMessage());
                }
            }
            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[Alerter] 扫描器完成 - scanned={}, fired={}, skipped={}, cost={}ms",
                    entries.size(), fired, skipped, costMs);
        } catch (Exception e) {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[Alerter] 扫描器异常 - error={}, cost={}ms", e.getMessage(), costMs);
        }
    }
}
