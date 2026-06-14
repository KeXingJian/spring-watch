package com.springwatch.alerter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    public void submit(com.springwatch.model.event.MetricEvent event) {
        if (executor == null) {
            log.warn("[Alerter] 线程池未初始化, 同步执行 - appid={}", event.getAppid());
            engine.process(event);
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    engine.process(event);
                } catch (Throwable t) {
                    log.error("[Alerter] 评估异常 - appid={}, metric={}, error={}",
                            event.getAppid(), event.getMetricName(), t.getMessage(), t);
                }
            });
        } catch (Exception e) {
            log.warn("[Alerter] 提交评估任务失败, 降级为同步 - appid={}, error={}",
                    event.getAppid(), e.getMessage());
            engine.process(event);
        }
    }
}
