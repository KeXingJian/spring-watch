package com.springwatch.collector.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class HostThrottler {

    private final AppScheduleProperties properties;
    private final ConcurrentHashMap<String, Semaphore> hostSemaphores = new ConcurrentHashMap<>();

    public boolean tryAcquire(String host, long timeoutMs) {
        Semaphore sem = hostSemaphores.computeIfAbsent(host, _ -> {
            Semaphore created = new Semaphore(properties.getPerHostConcurrent());
            log.info("[spring-watch: 主机限流器创建 - host={}, permits={}]", host, properties.getPerHostConcurrent());
            return created;
        });
        int before = sem.availablePermits();
        try {
            boolean acquired = sem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (acquired) {
                log.debug("[spring-watch: 主机限流获取成功 - host={}, 剩余={}->{}]", host, before, sem.availablePermits());
            } else {
                log.warn("[spring-watch: 主机限流获取超时 - host={}, 剩余={}, timeoutMs={}]", host, before, timeoutMs);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[spring-watch: 主机限流获取中断 - host={}]", host);
            return false;
        }
    }

    public void release(String host) {
        Semaphore sem = hostSemaphores.get(host);
        if (sem != null) {
            int before = sem.availablePermits();
            sem.release();
            log.debug("[spring-watch: 主机限流释放 - host={}, 剩余={}->{}]", host, before, sem.availablePermits());
        } else {
            log.warn("[spring-watch: 主机限流释放失败 - host={}, 限流器不存在]", host);
        }
    }

    /**
     * P1-5: 删除应用时彻底清理该 host 的限流器 entry，
     * 避免 hostSemaphores 在长生命周期中持续累积导致内存泄漏。
     * 仅在所有 permit 都已归还且无等待线程时移除。
     */
    public void cleanup(String host) {
        Semaphore sem = hostSemaphores.get(host);
        if (sem == null) {
            return;
        }
        if (sem.availablePermits() != properties.getPerHostConcurrent() || sem.getQueueLength() != 0) {
            log.warn("[spring-watch: 主机限流器清理失败 - host={}, available={}, waiting={}, max={}",
                    host, sem.availablePermits(), sem.getQueueLength(), properties.getPerHostConcurrent());
            return;
        }
        Semaphore removed = hostSemaphores.remove(host);
        if (removed != null) {
            log.info("[spring-watch: 主机限流器清理 - host={}, activeHosts={}]", host, hostSemaphores.size());
        }
    }

    public int activeHosts() {
        return hostSemaphores.size();
    }
}
