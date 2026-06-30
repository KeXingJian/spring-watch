package com.springwatch.alerter;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * P1-10: 邮件发送独立线程池。
 * 把 SMTP 调用从 AsyncAlertExecutor 拆出，避免 SMTP 慢响应阻塞告警评估；
 * 通过 Semaphore 限制最大并发 SMTP 连接数。
 */
@Slf4j
@Component
public class AsyncMailExecutor {

    @Value("${spring-watch.alert.mail.executor.pool-size:4}")
    private int poolSize;

    @Value("${spring-watch.alert.mail.executor.shutdown-seconds:3}")
    private int shutdownSeconds;

    private ExecutorService executor;
    private Semaphore semaphore;

    public void start() {
        if (executor == null) {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            this.semaphore = new Semaphore(poolSize);
            log.info("[Alerter] 邮件独立线程池启动 - maxConcurrent={}, threadType=virtual-per-task", poolSize);
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(shutdownSeconds, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("[Alerter] 邮件独立线程池关闭");
    }

    /**
     * 提交一个邮件发送任务，由独立线程池执行。
     * 任务本身负责实际的 SMTP 调用（如 AlertNotifier.sendEmail）。
     */
    public void submit(Runnable task) {
        if (executor == null) {
            start();
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
            log.warn("[Alerter] 提交邮件任务失败, 降级为同步 - error={}", e.getMessage());
            task.run();
        }
    }

    public int availablePermits() {
        return semaphore == null ? poolSize : semaphore.availablePermits();
    }
}
