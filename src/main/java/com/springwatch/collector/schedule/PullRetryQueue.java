package com.springwatch.collector.schedule;

import com.springwatch.collector.AppPullTask;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.entity.MonitorStatus;
import com.springwatch.repository.MonitorAppRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class PullRetryQueue {

    private final HostThrottler throttler;
    private final AppPullTask appPullTask;
    private final MonitorAppRepository repository;
    private final AppScheduleProperties properties;

    private final PriorityBlockingQueue<RetryPull> queue = new PriorityBlockingQueue<>(
            64, Comparator.comparing(RetryPull::enqueueTime));
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private ExecutorService drainerPool;

    public PullRetryQueue(HostThrottler throttler,
                          AppPullTask appPullTask,
                          MonitorAppRepository repository,
                          AppScheduleProperties properties) {
        this.throttler = throttler;
        this.appPullTask = appPullTask;
        this.repository = repository;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        ThreadFactory schedTf = Thread.ofVirtual().name("pull-retry-sched-", 0).factory();
        ThreadFactory drainTf = Thread.ofVirtual().name("pull-retry-drain-", 0).factory();
        this.scheduler = Executors.newScheduledThreadPool(1, schedTf);
        this.drainerPool = Executors.newFixedThreadPool(properties.getRetry().getDrainerCount(), drainTf);
        for (int i = 0; i < properties.getRetry().getDrainerCount(); i++) {
            drainerPool.submit(this::drainLoop);
        }
        running.set(true);
        log.info("[spring-watch: 重投队列启动 - drainerCount={}, maxAttempts={}, baseBackoffMs=500, maxBackoffShift=6]",
                properties.getRetry().getDrainerCount(), properties.getRetry().getMaxAttempts());
    }

    @PreDestroy
    void stop() {
        log.info("[spring-watch: 重投队列关闭 - pending={}]", queue.size());
        running.set(false);
        if (drainerPool != null) {
            drainerPool.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public void enqueue(RetryPull pull) {
        queue.offer(pull);
        log.debug("[spring-watch: 入重投队列 - appid={}, host={}, attempt={}, pending={}]",
                pull.appid(), pull.host(), pull.attempt(), queue.size());
    }


    private void drainLoop() {
        String name = Thread.currentThread().getName();
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                RetryPull pull = queue.take();
                processPull(pull);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                log.warn("[spring-watch: 重投drainer异常 - drainer={}, error={}]", name, t.getMessage(), t);
            }
        }
        log.info("[spring-watch: 重投drainer退出 - drainer={}]", name);
    }

    private void processPull(RetryPull pull) {
        MonitorApp app = repository.findByAppid(pull.appid()).orElse(null);
        if (app == null) {
            log.info("[spring-watch: 重投跳过 - appid={} 已删除]", pull.appid());
            return;
        }
        if (MonitorStatus.isPaused(app.getStatus())) {
            log.debug("[spring-watch: 重投跳过 - appid={} 已暂停]", pull.appid());
            return;
        }
        String host = extractHost(app);

        if (!throttler.tryAcquire(host, 0)) {
            int max = properties.getRetry().getMaxAttempts();
            if (pull.attempt() >= max) {
                log.warn("[spring-watch: 重投耗尽 - appid={}, host={}, attempts={}, 放弃, 等下个周期]",
                        pull.appid(), host, pull.attempt() + 1);
                return;
            }
            RetryPull next = pull.withIncrementedAttempt();
            long backoff = next.backoffMs();
            log.debug("[spring-watch: 重投仍被限流 - appid={}, host={}, attempt={}->{}, backoffMs={}]",
                    pull.appid(), host, pull.attempt(), next.attempt(), backoff);
            scheduler.schedule(() -> queue.offer(next), backoff, TimeUnit.MILLISECONDS);
            return;
        }

        try {
            log.info("[spring-watch: 重投执行 - appid={}, host={}, attempt={}]",
                    pull.appid(), host, pull.attempt());
            appPullTask.doHeavyWork(pull.appid());
        } catch (Throwable t) {
            log.warn("[spring-watch: 重投执行异常 - appid={}, error={}]", pull.appid(), t.getMessage(), t);
        } finally {
            throttler.release(host);
        }
    }

    private String extractHost(MonitorApp app) {
        String endpoint = app.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return "localhost";
        }
        try {
            URI uri = URI.create(endpoint);
            return uri.getHost() != null ? uri.getHost() : "localhost";
        } catch (Exception e) {
            return "localhost";
        }
    }
}
