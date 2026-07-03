package com.springwatch.collector.schedule;

import com.springwatch.collector.AppPullTask;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.entity.MonitorStatus;
import com.springwatch.repository.MonitorAppRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class PullRetryQueue {

    private final CollectorThrottler throttler;
    private final AppPullTask appPullTask;
    private final MonitorAppRepository repository;
    private final AppScheduleProperties properties;

    private final PriorityBlockingQueue<RetryPull> queue = new PriorityBlockingQueue<>(
            64, Comparator.comparing(RetryPull::enqueueTime));
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final int maxQueueSize;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private ExecutorService drainerPool;

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private final Counter enqueuedCounter;
    private final Counter rejectedCounter;
    private final Counter droppedCounter;
    private final Counter dedupedCounter;

    public PullRetryQueue(CollectorThrottler throttler,
                          @Lazy AppPullTask appPullTask,
                          MonitorAppRepository repository,
                          AppScheduleProperties properties,
                          MeterRegistry meterRegistry) {
        this.throttler = throttler;
        this.appPullTask = appPullTask;
        this.repository = repository;
        this.properties = properties;
        this.maxQueueSize = properties.getRetry().getMaxQueueSize();
        this.enqueuedCounter = Counter.builder("spring.watch.collector.retry.enqueued")
                .description("拉取重投入队次数")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("spring.watch.collector.retry.rejected")
                .description("拉取重投被拒(队列满)次数")
                .register(meterRegistry);
        this.droppedCounter = Counter.builder("spring.watch.collector.retry.dropped")
                .description("拉取重投丢弃次数")
                .register(meterRegistry);
        this.dedupedCounter = Counter.builder("spring.watch.collector.retry.deduped")
                .description("拉取重投去重丢弃次数(appid已在重投中)")
                .register(meterRegistry);
        meterRegistry.gauge("spring.watch.collector.retry.queue.size", queueSize);
    }

    @PostConstruct
    void start() {
        ThreadFactory schedTf = Thread.ofVirtual().name("pull-retry-sched-", 0).factory();
        ThreadFactory drainTf = Thread.ofVirtual().name("pull-retry-drain-", 0).factory();
        this.scheduler = Executors.newScheduledThreadPool(1, schedTf);
        this.drainerPool = Executors.newFixedThreadPool(properties.getRetry().getDrainerCount(), drainTf);
        running.set(true);
        log.info("[kxj: 重投队列启动 - drainerCount={}, maxAttempts={}, maxQueueSize={}, baseBackoffMs=500, maxBackoffShift=6]",
                properties.getRetry().getDrainerCount(), properties.getRetry().getMaxAttempts(), maxQueueSize);
        for (int i = 0; i < properties.getRetry().getDrainerCount(); i++) {
            final int drainerId = i;
            drainerPool.submit(() -> drainLoop("pull-retry-drain-" + drainerId));
        }
    }

    @PreDestroy
    void stop() {
        log.info("[kxj: 重投队列关闭 - pending={}]", queueSize.get());
        running.set(false);
        if (drainerPool != null) {
            drainerPool.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public boolean enqueue(RetryPull pull) {
        if (!inFlight.add(pull.appid())) {
            dedupedCounter.increment();
            log.debug("[kxj: 重投去重 - appid={} 已在重投中, 丢弃新重投, host={}, attempt={}]",
                    pull.appid(), pull.host(), pull.attempt());
            return false;
        }
        if (queueSize.get() >= maxQueueSize) {
            inFlight.remove(pull.appid());
            rejectedCounter.increment();
            droppedCounter.increment();
            log.warn("[kxj: 重投队列已满, 丢弃 - appid={}, host={}, attempt={}, pending={}/{}]",
                    pull.appid(), pull.host(), pull.attempt(), queueSize.get(), maxQueueSize);
            return false;
        }
        if (queue.offer(pull)) {
            queueSize.incrementAndGet();
            enqueuedCounter.increment();
            log.debug("[kxj: 入重投队列 - appid={}, host={}, attempt={}, pending={}/{}]",
                    pull.appid(), pull.host(), pull.attempt(), queueSize.get(), maxQueueSize);
            return true;
        }
        inFlight.remove(pull.appid());
        return false;
    }

    public int pending() {
        return queueSize.get();
    }

    private RetryPull pollHead() {
        RetryPull p = queue.poll();
        if (p != null) {
            queueSize.decrementAndGet();
            inFlight.remove(p.appid());
        }
        return p;
    }

    private void drainLoop(String drainerName) {
        log.info("[kxj: 重投drainer启动完成 - drainer={}]", drainerName);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                RetryPull pull = pollHead();
                if (pull == null) {
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                log.info("[kxj: drainer出队 - drainer={}, appid={}, attempt={}, pending={}]",
                        drainerName, pull.appid(), pull.attempt(), queueSize.get());
                processPull(pull, drainerName);
            } catch (Throwable t) {
                log.warn("[kxj: 重投drainer异常 - drainer={}, error={}]", drainerName, t.getMessage(), t);
            }
        }
        log.info("[kxj: 重投drainer退出 - drainer={}]", drainerName);
    }

    private void processPull(RetryPull pull, String drainerName) {
        MonitorApp app = repository.findByAppid(pull.appid()).orElse(null);
        if (app == null) {
            log.info("[kxj: 重投跳过 - drainer={}, appid={} 已删除]", drainerName, pull.appid());
            return;
        }
        if (MonitorStatus.isPaused(app.getStatus())) {
            log.debug("[kxj: 重投跳过 - drainer={}, appid={} 已暂停]", drainerName, pull.appid());
            return;
        }
        String host = extractHost(app);

        if (!throttler.tryAcquire(host, 0)) {
            int max = properties.getRetry().getMaxAttempts();
            if (pull.attempt() >= max) {
                log.warn("[kxj: 重投耗尽 - drainer={}, appid={}, host={}, attempts={}, 放弃, 等下个周期]",
                        drainerName, pull.appid(), host, pull.attempt() + 1);
                return;
            }
            RetryPull next = pull.withIncrementedAttempt();
            long backoff = next.backoffMs();
            log.info("[kxj: 重投仍被限流 - drainer={}, appid={}, host={}, attempt={}->{}, backoffMs={}, enqueueAt~={}]",
                    drainerName, pull.appid(), host, pull.attempt(), next.attempt(), backoff, Instant.now().plusMillis(backoff));
            scheduler.schedule(() -> enqueue(next), backoff, TimeUnit.MILLISECONDS);
            return;
        }

        try {
            long retryStart = System.nanoTime();
            log.info("[kxj: 重投执行 - drainer={}, appid={}, host={}, attempt={}, path=retry]",
                    drainerName, pull.appid(), host, pull.attempt());
            boolean reachable = appPullTask.doHeavyWork(pull.appid());
            if (!reachable) {
                appPullTask.markUnreachable(app);
            }else {
                appPullTask.recordCost(retryStart, pull.appid(), "retry:" + pull.appid());
            }

        } catch (Throwable t) {
            log.warn("[kxj: 重投执行异常 - drainer={}, appid={}, error={}]", drainerName, pull.appid(), t.getMessage(), t);
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
