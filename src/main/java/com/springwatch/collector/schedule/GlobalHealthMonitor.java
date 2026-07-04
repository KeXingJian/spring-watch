package com.springwatch.collector.schedule;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class GlobalHealthMonitor {

    private static final int WINDOW_SIZE = 100;

    private final AppScheduleProperties properties;
    private final MeterRegistry meterRegistry;

    private final long[] latencyRing = new long[WINDOW_SIZE];
    private final boolean[] outcomeRing = new boolean[WINDOW_SIZE];
    private final AtomicInteger cursor = new AtomicInteger(0);
    private final AtomicInteger totalSamples = new AtomicInteger(0);

    @Getter
    private volatile double slowFactor = 1.0;
    private volatile long lastTickNs = System.nanoTime();

    private Counter decreaseCounter;
    private Counter increaseCounter;

    @PostConstruct
    void init() {
        this.decreaseCounter = Counter.builder("spring.watch.collector.global.decrease")
                .description("全局慢因子乘法退避次数(系统变慢)")
                .register(meterRegistry);
        this.increaseCounter = Counter.builder("spring.watch.collector.global.increase")
                .description("全局慢因子加法恢复次数(系统变好)")
                .register(meterRegistry);
        Gauge.builder("spring.watch.collector.global.slow_factor", this, m -> m.slowFactor)
                .description("全局慢因子(1.0=基线,越大调度越慢)")
                .register(meterRegistry);
        log.info("[kxj: GlobalHealthMonitor 初始化 - windowSize={}, slowFactor=[{}, {}], p95Degrade={}ms, p95Recover={}ms, errDegrade={}%, errRecover={}%, healthTickMs={}]",
                WINDOW_SIZE,
                properties.getSchedule().getSlowFactorMin(),
                properties.getSchedule().getSlowFactorMax(),
                properties.getSchedule().getP95DegradeMs(),
                properties.getSchedule().getP95RecoverMs(),
                properties.getSchedule().getErrorRateDegradePercent(),
                properties.getSchedule().getErrorRateRecoverPercent(),
                properties.getSchedule().getHealthTickMs());
    }

    public synchronized void recordPull(long latencyMs, boolean success) {
        int idx = cursor.getAndIncrement() % WINDOW_SIZE;
        latencyRing[idx] = latencyMs;
        outcomeRing[idx] = success;
        totalSamples.incrementAndGet();
    }

    public long currentIntervalMs(long baseIntervalMs) {
        maybeTick();
        long v = (long) (baseIntervalMs * slowFactor);
        return Math.max(1000L, v);
    }

    public synchronized void maybeTick() {
        long now = System.nanoTime();
        long tickIntervalNs = properties.getSchedule().getHealthTickMs() * 1_000_000L;
        if (now - lastTickNs < tickIntervalNs) {
            return;
        }
        lastTickNs = now;
        recomputeSlowFactor();
    }


    private void recomputeSlowFactor() {
        int n = Math.min(totalSamples.get(), WINDOW_SIZE);
        if (n < 10) {
            return;
        }

        long[] sortedLatencies = Arrays.copyOf(latencyRing, n);
        Arrays.sort(sortedLatencies);
        long p95 = sortedLatencies[(int) (n * 0.95)];

        int failures = 0;
        for (int i = 0; i < n; i++) {
            if (!outcomeRing[i]) {
                failures++;
            }
        }
        double errorRate = (double) failures / n;

        long p95Degrade = properties.getSchedule().getP95DegradeMs();
        long p95Recover = properties.getSchedule().getP95RecoverMs();
        double errDegrade = properties.getSchedule().getErrorRateDegradePercent() / 100.0;
        double errRecover = properties.getSchedule().getErrorRateRecoverPercent() / 100.0;
        double slowMax = properties.getSchedule().getSlowFactorMax();
        double slowMin = properties.getSchedule().getSlowFactorMin();
        double degradeMul = properties.getSchedule().getDegradeMultiplier();
        double recoverMul = properties.getSchedule().getRecoverMultiplier();

        double oldFactor = slowFactor;
        double newFactor = oldFactor;

        boolean degraded = p95 > p95Degrade || errorRate > errDegrade;
        boolean recovered = p95 < p95Recover && errorRate < errRecover;

        if (degraded) {
            newFactor = Math.min(oldFactor * degradeMul, slowMax);
            decreaseCounter.increment();
            slowFactor = newFactor;
            log.warn("[kxj: 全局慢因子退避 - slowFactor {} -> {}, p95={}ms, errRate={}%, threshold=p95>{}ms OR err>{}%]",
                    oldFactor, newFactor, p95, (int) (errorRate * 100), p95Degrade, (int) (errDegrade * 100));
        } else if (recovered) {
            newFactor = Math.max(oldFactor * recoverMul, slowMin);
            increaseCounter.increment();
            slowFactor = newFactor;
            log.info("[kxj: 全局慢因子恢复 - slowFactor {} -> {}, p95={}ms, errRate={}%, threshold=p95<{}ms AND err<{}%]",
                    oldFactor, newFactor, p95, (int) (errorRate * 100), p95Recover, (int) (errRecover * 100));
        }
    }
}
