package com.springwatch.collector.schedule;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectorThrottler {

    private final AppScheduleProperties properties;
    private final MeterRegistry meterRegistry;

    private final Semaphore globalSemaphore = new Semaphore(0, true);
    private final ConcurrentHashMap<String, Semaphore> hostSemaphores = new ConcurrentHashMap<>();
    private volatile int globalPermits;
    private volatile int perHostPermits;

    @PostConstruct
    void init() {
        this.globalPermits = properties.getGlobalConcurrent();
        this.perHostPermits = properties.getPerHostConcurrent();
        globalSemaphore.release(globalPermits);
        log.info("[kxj: CollectorThrottler 初始化 - globalPermits={}, perHostPermits={}, fair=true, 两级限流]",
                globalPermits, perHostPermits);
        meterRegistry.gauge("spring.watch.collector.throttler.global.available",
                this, c -> c.globalSemaphore.availablePermits());
        meterRegistry.gauge("spring.watch.collector.throttler.host.active",
                this, c -> c.hostSemaphores.size());
    }

    public boolean tryAcquire(String host, long timeoutMs) {
        boolean globalOk;
        try {
            globalOk = globalSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!globalOk) {
            log.debug("[kxj: 全局限流拒绝 - host={}, globalAvailable={}/{}]",
                    host, globalSemaphore.availablePermits(), globalPermits);
            return false;
        }

        Semaphore hostSem = hostSemaphores.computeIfAbsent(host, _ -> {
            Semaphore created = new Semaphore(perHostPermits, true);
            log.info("[kxj: 主机限流器创建 - host={}, permits={}]", host, perHostPermits);
            return created;
        });
        if (!hostSem.tryAcquire()) {
            globalSemaphore.release();
            log.debug("[kxj: 主机限流拒绝 - host={}, hostAvailable={}/{}]",
                    host, hostSem.availablePermits(), perHostPermits);
            return false;
        }
        return true;
    }

    public void release(String host) {
        Semaphore hostSem = hostSemaphores.get(host);
        if (hostSem != null) {
            hostSem.release();
        } else {
            log.warn("[kxj: 主机限流器不存在 - host={}]", host);
        }
        globalSemaphore.release();
    }

    public void cleanup(String host) {
        Semaphore sem = hostSemaphores.get(host);
        if (sem == null) {
            return;
        }
        if (sem.availablePermits() != perHostPermits || sem.getQueueLength() != 0) {
            log.warn("[kxj: 主机限流器清理失败 - host={}, available={}, waiting={}, max={}",
                    host, sem.availablePermits(), sem.getQueueLength(), perHostPermits);
            return;
        }
        Semaphore removed = hostSemaphores.remove(host);
        if (removed != null) {
            log.info("[kxj: 主机限流器清理 - host={}, activeHosts={}]", host, hostSemaphores.size());
        }
    }

    public int activeHosts() {
        return hostSemaphores.size();
    }
}
