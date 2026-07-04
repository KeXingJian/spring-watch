package com.springwatch.collector.schedule;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class HostCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public enum Outcome { SUCCESS, SLOW, TIMEOUT, ERROR }

    private final AppScheduleProperties properties;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, HostState> hosts = new ConcurrentHashMap<>();
    private Counter openedCounter;
    private Counter closedCounter;
    private Counter halfOpenCounter;
    private Counter fastFailCounter;
    private Counter probeCounter;

    @PostConstruct
    void init() {
        this.openedCounter = Counter.builder("spring.watch.collector.cb.opened")
                .description("熔断器从 CLOSED 转 OPEN 次数")
                .register(meterRegistry);
        this.closedCounter = Counter.builder("spring.watch.collector.cb.closed")
                .description("熔断器恢复 CLOSED 次数")
                .register(meterRegistry);
        this.halfOpenCounter = Counter.builder("spring.watch.collector.cb.half_open")
                .description("熔断器进入 HALF_OPEN 次数")
                .register(meterRegistry);
        this.fastFailCounter = Counter.builder("spring.watch.collector.cb.fast_fail")
                .description("熔断器快速失败次数(OPEN 直接拒绝,不入队超时)")
                .register(meterRegistry);
        this.probeCounter = Counter.builder("spring.watch.collector.cb.probe")
                .description("熔断器 HALF_OPEN 探针次数")
                .register(meterRegistry);
        log.info("[kxj: HostCircuitBreaker 初始化 - windowSize={}, slowThresholdMs={}, failureRatePct={}%, slowRatePct={}%, coolDownMs={}->{}]",
                properties.getCircuitBreaker().getWindowSize(),
                properties.getCircuitBreaker().getSlowThresholdMs(),
                properties.getCircuitBreaker().getFailureRatePercent(),
                properties.getCircuitBreaker().getSlowRatePercent(),
                properties.getCircuitBreaker().getInitialCoolDownMs(),
                properties.getCircuitBreaker().getMaxCoolDownMs());
    }

    public boolean tryAcquire(String host) {
        HostState s = hosts.computeIfAbsent(host, _ -> new HostState(properties.getCircuitBreaker().getWindowSize()));
        long nowNs = System.nanoTime();

        State current = s.state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            long coolDownNs = s.currentCoolDownMs.get() * 1_000_000L;
            long openSinceNs = s.lastStateChangeNs.get();
            if (nowNs - openSinceNs < coolDownNs) {
                fastFailCounter.increment();
                return false;
            }
            if (s.state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                s.lastStateChangeNs.set(nowNs);
                halfOpenCounter.increment();
                log.info("[kxj: 熔断器转 HALF_OPEN - host={}, 冷却={}ms]", host, s.currentCoolDownMs.get());
            }
            boolean got = s.probeSlot.compareAndSet(0, 1);
            if (got) {
                probeCounter.increment();
            }
            return got;
        }
        boolean got = s.probeSlot.compareAndSet(0, 1);
        if (got) {
            probeCounter.increment();
        }
        return got;
    }

    public void recordOutcome(String host, Outcome outcome, long latencyMs) {
        HostState s = hosts.get(host);
        if (s == null) {
            return;
        }
        State current = s.state.get();

        if (current == State.HALF_OPEN) {
            s.probeSlot.set(0);
            if (outcome == Outcome.SUCCESS) {
                if (s.state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    s.lastStateChangeNs.set(System.nanoTime());
                    s.currentCoolDownMs.set(properties.getCircuitBreaker().getInitialCoolDownMs());
                    s.window.clear();
                    closedCounter.increment();
                    log.info("[kxj: 熔断器恢复 CLOSED - host={}, 探针通过, latencyMs={}]", host, latencyMs);
                }
            } else {
                if (s.state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                    s.lastStateChangeNs.set(System.nanoTime());
                    long nextCool = Math.min(s.currentCoolDownMs.get() * 2,
                            properties.getCircuitBreaker().getMaxCoolDownMs());
                    s.currentCoolDownMs.set(nextCool);
                    openedCounter.increment();
                    log.warn("[kxj: 熔断器重开 OPEN - host={}, 探针失败 outcome={}, coolDownMs={}->{}]",
                            host, outcome, properties.getCircuitBreaker().getInitialCoolDownMs(), nextCool);
                }
            }
            return;
        }

        if (current == State.CLOSED) {
            int slot = s.window.nextSlot();
            s.window.slots[slot] = outcome;
            s.window.count++;
            if (s.window.count >= s.window.slots.length) {
                evaluateAndMaybeOpen(host, s);
            }
        }
    }

    private void evaluateAndMaybeOpen(String host, HostState s) {
        int total = s.window.slots.length;
        int bad = 0;
        int slow = 0;
        int consecutiveTimeouts = 0;
        int currentStreak = 0;
        int cursor = (s.window.cursor - 1 + s.window.slots.length) % s.window.slots.length;
        for (int i = 0; i < total; i++) {
            int idx = (cursor - i + s.window.slots.length) % s.window.slots.length;
            Outcome o = s.window.slots[idx];
            if (o == null) {
                continue;
            }
            if (o == Outcome.TIMEOUT) {
                bad++;
                currentStreak++;
                consecutiveTimeouts = Math.max(consecutiveTimeouts, currentStreak);
            } else {
                if (o == Outcome.ERROR) {
                    bad++;
                } else if (o == Outcome.SLOW) {
                    slow++;
                }
                currentStreak = 0;
            }
        }
        int failureRatePct = properties.getCircuitBreaker().getFailureRatePercent();
        int slowRatePct = properties.getCircuitBreaker().getSlowRatePercent();
        int consecutiveTo = properties.getCircuitBreaker().getConsecutiveTimeoutsToOpen();
        int badPct = bad * 100 / total;
        int slowPct = (bad + slow) * 100 / total;

        boolean tripByRate = badPct >= failureRatePct;
        boolean tripBySlow = slowPct >= slowRatePct;
        boolean tripByConsecutive = consecutiveTo > 0 && consecutiveTimeouts >= consecutiveTo;

        if (tripByRate || tripBySlow || tripByConsecutive) {
            if (s.state.compareAndSet(State.CLOSED, State.OPEN)) {
                s.lastStateChangeNs.set(System.nanoTime());
                s.currentCoolDownMs.set(properties.getCircuitBreaker().getInitialCoolDownMs());
                s.window.clear();
                openedCounter.increment();
                log.warn("[kxj: 熔断器打开 OPEN - host={}, badPct={}%, slowPct={}%, consecutiveTimeouts={}, reason={}]",
                        host, badPct, slowPct, consecutiveTimeouts,
                        tripByConsecutive ? "consecutiveTimeouts" :
                                (tripByRate ? "failureRate" : "slowRate"));
            }
        }
    }

    public State stateOf(String host) {
        HostState s = hosts.get(host);
        return s == null ? State.CLOSED : s.state.get();
    }

    public long coolDownMsOf(String host) {
        HostState s = hosts.get(host);
        return s == null ? 0L : s.currentCoolDownMs.get();
    }



    private static final class HostState {
        final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
        final AtomicLong lastStateChangeNs = new AtomicLong(System.nanoTime());
        final AtomicLong currentCoolDownMs = new AtomicLong(10_000L);
        final AtomicInteger probeSlot = new AtomicInteger(0);
        final Window window;

        HostState(int windowSize) {
            this.window = new Window(windowSize);
        }
    }

    private static final class Window {
        final Outcome[] slots;
        int count = 0;
        int cursor = 0;

        Window(int size) {
            this.slots = new Outcome[size];
        }

        int nextSlot() {
            int idx = cursor;
            cursor = (cursor + 1) % slots.length;
            return idx;
        }

        void clear() {
            count = 0;
            cursor = 0;
            Arrays.fill(slots, null);
        }
    }


}
