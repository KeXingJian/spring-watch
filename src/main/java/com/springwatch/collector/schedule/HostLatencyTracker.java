package com.springwatch.collector.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class HostLatencyTracker {

    private final AppScheduleProperties properties;
    private final ConcurrentHashMap<String, HostLatency> hosts = new ConcurrentHashMap<>();

    public void record(String host, long latencyMs, HostCircuitBreaker.Outcome outcome) {
        if (host == null) {
            return;
        }
        HostLatency h = hosts.computeIfAbsent(host, _ -> new HostLatency(
                properties.getHttp().getMinReadTimeoutMs(),
                properties.getHttp().getMaxReadTimeoutMs(),
                properties.getHttp().getDefaultReadTimeoutMs()));
        h.record(latencyMs, outcome);
    }

    public int adaptiveTimeoutMs(String host) {
        HostLatency h = hosts.get(host);
        if (h == null) {
            return properties.getHttp().getDefaultReadTimeoutMs();
        }
        return h.adaptiveTimeoutMs();
    }

    private static final class HostLatency {
        final AtomicLong ewmaLatencyMs = new AtomicLong(0L);
        final AtomicLong ewmaBadPct = new AtomicLong(0L);
        final long minReadTimeoutMs;
        final long maxReadTimeoutMs;
        final long defaultReadTimeoutMs;
        volatile boolean warmed = false;

        HostLatency(long minReadTimeoutMs, long maxReadTimeoutMs, long defaultReadTimeoutMs) {
            this.minReadTimeoutMs = minReadTimeoutMs;
            this.maxReadTimeoutMs = maxReadTimeoutMs;
            this.defaultReadTimeoutMs = defaultReadTimeoutMs;
        }

        void record(long latencyMs, HostCircuitBreaker.Outcome outcome) {
            long prev = ewmaLatencyMs.get();
            long next;
            if (!warmed) {
                if (prev == 0L) {
                    next = latencyMs;
                } else {
                    next = (long) (prev * 0.7 + latencyMs * 0.3);
                    warmed = true;
                }
            } else {
                next = (long) (prev * 0.8 + latencyMs * 0.2);
            }
            ewmaLatencyMs.set(next);

            long prevBad = ewmaBadPct.get();
            int sampleBad = (outcome == HostCircuitBreaker.Outcome.SUCCESS) ? 0 : 100;
            long nextBad = (long) (prevBad * 0.85 + sampleBad * 0.15);
            ewmaBadPct.set(nextBad);
        }

        int adaptiveTimeoutMs() {
            long ewma = ewmaLatencyMs.get();
            long base = ewma <= 0L ? defaultReadTimeoutMs : ewma * 5 / 2;

            long badPct = ewmaBadPct.get();
            double shrink = 1.0 - (badPct / 100.0) * 0.7;
            long target = (long) (base * shrink);

            if (target < minReadTimeoutMs) {
                return (int) minReadTimeoutMs;
            }
            if (target > maxReadTimeoutMs) {
                return (int) maxReadTimeoutMs;
            }
            return (int) target;
        }
    }
}
