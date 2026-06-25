package com.springwatch.alerter;

import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.MetricEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 异步告警评估执行器。
 * P0-3: 改用 per-task 虚拟线程执行器 + Semaphore 限制并发，
 * 避免 newFixedThreadPool 包裹的无界 LinkedBlockingQueue 在突发期持有大量 MetricEvent。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncAlertExecutor {

    private final AlertEngine engine;

    @Value("${spring-watch.alert.executor.pool-size:8}")
    private int poolSize;

    private ExecutorService executor;
    private Semaphore semaphore;

    @PostConstruct
    void init() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.semaphore = new Semaphore(poolSize);
        log.info("[Alerter] 异步告警评估启动 - maxConcurrent={}, threadType=virtual-per-task", poolSize);
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
        log.info("[Alerter] 异步告警评估线程池关闭");
    }

    private void runWrapped(Runnable task) {
        if (executor == null) {
            task.run();
            return;
        }
        try {
            semaphore.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            task.run();
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    semaphore.release();
                }
            });
        } catch (Exception e) {
            semaphore.release();
            log.warn("[Alerter] 提交评估任务失败, 降级为同步 - error={}", e.getMessage());
            task.run();
        }
    }

    public void submit(MetricEvent event) {
        if (event == null) {
            log.debug("[Alerter] submit(MetricEvent) event为空, 跳过");
            return;
        }
        runWrapped(() -> {
            try {
                if (log.isTraceEnabled()) {
                    log.trace("[Alerter] 评估任务开始 - appid={}, metric={}", event.getAppid(), event.getMetricName());
                }
                engine.process(event);
            } catch (Throwable t) {
                log.error("[Alerter] 评估异常 - appid={}, metric={}, error={}",
                        event.getAppid(), event.getMetricName(), t.getMessage(), t);
            }
        });
    }

    /**
     * kxj: 异步提交日志事件评估-与metric使用同一线程池
     */
    public void submit(com.springwatch.model.event.LogEvent event) {
        if (event == null || event.getAppid() == null) {
            log.debug("[Alerter] submit(LogEvent) event/appid为空, 跳过 - event={}", event);
            return;
        }
        runWrapped(() -> {
            try {
                if (log.isTraceEnabled()) {
                    log.trace("[Alerter] 日志评估任务开始 - appid={}, fingerprint={}", event.getAppid(), event.getFingerprint());
                }
                engine.process(event);
            } catch (Throwable t) {
                log.error("[Alerter] 日志评估异常 - appid={}, fingerprint={}, error={}",
                        event.getAppid(), event.getFingerprint(), t.getMessage(), t);
            }
        });
    }

    /**
     * kxj: 扫描器提交-PendingStateScanner专用,与MetricEvent走同一线程池
     */
    public void submitFromScanner(AlertRule rule, Long appid, Instant firstBreachAt, long triggerCount, Instant now) {
        if (rule == null || appid == null) {
            return;
        }
        runWrapped(() -> {
            try {
                if (log.isDebugEnabled()) {
                    log.debug("[Alerter] 扫描器评估任务开始 - ruleId={}, appid={}", rule.getId(), appid);
                }
                engine.fireFromScanner(rule, appid, firstBreachAt, triggerCount, now);
            } catch (Throwable t) {
                log.error("[Alerter] 扫描器评估异常 - ruleId={}, appid={}, error={}",
                        rule.getId(), appid, t.getMessage(), t);
            }
        });
    }
}
