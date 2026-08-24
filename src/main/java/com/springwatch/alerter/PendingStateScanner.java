package com.springwatch.alerter;

import com.springwatch.model.entity.AlertRule;
import com.springwatch.repository.AlertRuleRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "spring-watch.alert.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class PendingStateScanner {

    private final AlertStateStore stateStore;
    private final AlertRuleRepository ruleRepository;
    private final AsyncAlertExecutor alertExecutor;

    @Value("${spring-watch.alert.scan.batch-size:200}")
    private long batchSize;

    @Value("${spring-watch.alert.recover.stale-firing-seconds:86400}")
    private long staleFiringSeconds;

    private ExecutorService scanExecutor;

    @PostConstruct
    void init() {
        ThreadFactory tf = Thread.ofVirtual().name("pending-scanner-", 0).factory();
        this.scanExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1),
                tf,
                (r, exec) -> log.warn("[kxj: 扫描任务被丢弃 - workerBusy={}, queueSize={}, 等下个周期(下次触发会重扫)]",
                        exec.getActiveCount(), exec.getQueue().size()));
        log.info("[kxj: PENDING扫描器初始化 - batchSize={}, threadType=virtual, queueCapacity=1(防OOM,满了丢)]",
                batchSize);
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
     * 频率默认5秒,够细不至于漏判,又不会给本地缓存太大压力
     */
    @Scheduled(fixedDelayString = "${spring-watch.alert.scan.interval-ms:5000}")
    public void scan() {

        if (scanExecutor == null) {
            log.warn("[Alerter] 扫描线程池未初始化, 同步执行");
            doScan();
            return;
        }
        try {
            scanExecutor.execute(this::doScan);
        } catch (Exception e) {
            log.warn("[Alerter] 提交扫描任务失败, 跳过本轮 - error={}", e.getMessage());
        }
    }

    private void doScan() {
        long start = System.nanoTime();
        try {
            List<AlertStateStore.PendingEntry> entries = stateStore.scanStates(
                    batchSize, Duration.ofSeconds(staleFiringSeconds));
            if (entries.isEmpty()) {
                return;
            }
            log.debug("[Alerter] 扫描器发现待处理 - count={}", entries.size());
            Set<Long> ruleIds = entries.stream()
                    .map(AlertStateStore.PendingEntry::ruleId)
                    .collect(Collectors.toSet());
            Map<Long, AlertRule> ruleMap = ruleIds.isEmpty()
                    ? Map.of()
                    : ruleRepository.findAllById(ruleIds).stream()
                            .collect(Collectors.toMap(AlertRule::getId, r -> r, (a, b) -> a));
            int fired = 0;
            int recovered = 0;
            int skipped = 0;
            Instant now = Instant.now();
            for (AlertStateStore.PendingEntry entry : entries) {
                try {
                    AlertRule rule = ruleMap.get(entry.ruleId());
                    if (rule == null) {
                        log.debug("[Alerter] 扫描器跳过已删除规则 - ruleId={}, appid={}", entry.ruleId(), entry.appid());
                        stateStore.clear(entry.ruleId(), entry.appid());
                        skipped++;
                        continue;
                    }
                    if (!"enabled".equalsIgnoreCase(rule.getStatus())) {
                        log.debug("[Alerter] 扫描器跳过已禁用规则 - ruleId={}, appid={}, status={}",
                                entry.ruleId(), entry.appid(), rule.getStatus());
                        stateStore.clear(entry.ruleId(), entry.appid());
                        skipped++;
                        continue;
                    }
                    if (entry.isFiring()) {
                        alertExecutor.submitResolveFromScanner(rule, entry.appid(), now);
                        recovered++;
                    } else {
                        alertExecutor.submitFromScanner(rule, entry.appid(), entry.firstBreachAt(), entry.triggerCount(), now);
                        fired++;
                    }
                } catch (Exception e) {
                    log.warn("[Alerter] 扫描器处理单条失败 - ruleId={}, appid={}, error={}",
                            entry.ruleId(), entry.appid(), e.getMessage());
                }
            }
            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[Alerter] 扫描器完成 - scanned={}, fired={}, recovered={}, skipped={}, cost={}ms",
                    entries.size(), fired, recovered, skipped, costMs);
        } catch (Exception e) {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[Alerter] 扫描器异常 - error={}, cost={}ms", e.getMessage(), costMs);
        }
    }
}
