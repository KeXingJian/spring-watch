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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncAlertExecutor {

    private final AlertEngine engine;

    @Value("${spring-watch.alert.executor.pool-size:8}")
    private int poolSize;

    private ExecutorService executor;

    @PostConstruct
    void init() {
        ThreadFactory tf = Thread.ofVirtual().name("alert-eval-", 0).factory();
        this.executor = Executors.newFixedThreadPool(poolSize, tf);
        log.info("[Alerter] 异步告警评估线程池启动 - poolSize={}, threadType=virtual", poolSize);
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

    public void submit(MetricEvent event) {
        if (event == null) {
            log.debug("[Alerter] submit(MetricEvent) event为空, 跳过");
            return;
        }
        if (executor == null) {
            log.warn("[Alerter] 线程池未初始化, 同步执行 - appid={}", event.getAppid());
            engine.process(event);
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    log.trace("[Alerter] 评估任务开始 - appid={}, metric={}", event.getAppid(), event.getMetricName());
                    engine.process(event);
                } catch (Throwable t) {
                    log.error("[Alerter] 评估异常 - appid={}, metric={}, error={}",
                            event.getAppid(), event.getMetricName(), t.getMessage(), t);
                }
            });
            log.trace("[Alerter] 评估任务已提交 - appid={}, metric={}", event.getAppid(), event.getMetricName());
        } catch (Exception e) {
            log.warn("[Alerter] 提交评估任务失败, 降级为同步 - appid={}, error={}",
                    event.getAppid(), e.getMessage());
            engine.process(event);
        }
    }

    /**
     * kxj: 异步提交日志事件评估-与metric使用同一线程池
     */
    public void submit(com.springwatch.model.event.LogEvent event) {
        if (event == null || event.getAppid() == null) {
            log.debug("[Alerter] submit(LogEvent) event/appid为空, 跳过 - event={}", event);
            return;
        }
        if (executor == null) {
            log.warn("[Alerter] 线程池未初始化, 同步执行 - appid={}", event.getAppid());
            engine.process(event);
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    log.trace("[Alerter] 日志评估任务开始 - appid={}, fingerprint={}", event.getAppid(), event.getFingerprint());
                    engine.process(event);
                } catch (Throwable t) {
                    log.error("[Alerter] 日志评估异常 - appid={}, fingerprint={}, error={}",
                            event.getAppid(), event.getFingerprint(), t.getMessage(), t);
                }
            });
            log.trace("[Alerter] 日志评估任务已提交 - appid={}, fingerprint={}", event.getAppid(), event.getFingerprint());
        } catch (Exception e) {
            log.warn("[Alerter] 提交日志评估任务失败, 降级为同步 - appid={}, error={}",
                    event.getAppid(), e.getMessage());
            engine.process(event);
        }
    }

    /**
     * kxj: 扫描器提交-PendingStateScanner专用,与MetricEvent走同一线程池
     */
    public void submitFromScanner(AlertRule rule, Long appid, Instant firstBreachAt, long triggerCount, Instant now) {
        if (rule == null || appid == null) {
            return;
        }
        if (executor == null) {
            log.warn("[Alerter] 线程池未初始化, 同步执行 - ruleId={}, appid={}", rule.getId(), appid);
            engine.fireFromScanner(rule, appid, firstBreachAt, triggerCount, now);
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    log.debug("[Alerter] 扫描器评估任务开始 - ruleId={}, appid={}", rule.getId(), appid);
                    engine.fireFromScanner(rule, appid, firstBreachAt, triggerCount, now);
                } catch (Throwable t) {
                    log.error("[Alerter] 扫描器评估异常 - ruleId={}, appid={}, error={}",
                            rule.getId(), appid, t.getMessage(), t);
                }
            });
            log.debug("[Alerter] 扫描器评估任务已提交 - ruleId={}, appid={}", rule.getId(), appid);
        } catch (Exception e) {
            log.warn("[Alerter] 提交扫描器评估任务失败, 降级为同步 - ruleId={}, error={}",
                    rule.getId(), e.getMessage());
            engine.fireFromScanner(rule, appid, firstBreachAt, triggerCount, now);
        }
    }
}
