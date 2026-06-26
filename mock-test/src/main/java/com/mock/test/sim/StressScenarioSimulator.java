package com.mock.test.sim;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@ConditionalOnProperty(name = "mock.sim.enabled", havingValue = "true", matchIfMissing = true)
public class StressScenarioSimulator extends BaseSimulator {

    private final JdbcTemplate jdbcTemplate;

    @Value("${mock.sim.stress.pool-burst-ms:20000}")
    private long poolBurstMs;

    @Value("${mock.sim.stress.pool-concurrent:30}")
    private int poolConcurrent;

    @Value("${mock.sim.stress.pool-hold-seconds:4}")
    private long poolHoldSeconds;

    @Value("${mock.sim.stress.pool-burst-waves:3}")
    private int poolBurstWaves;

    @Value("${mock.sim.stress.pool-burst-wave-gap-ms:500}")
    private long poolBurstWaveGapMs;

    @Value("${mock.sim.stress.slow-query-ms:30000}")
    private long slowQueryMs;

    @Value("${mock.sim.stress.slow-query-duration-ms:3000}")
    private long slowQueryDurationMs;

    @Value("${mock.sim.stress.slow-query-count:8}")
    private int slowQueryCount;

    @Value("${mock.sim.stress.slow-query-interval-ms:150}")
    private long slowQueryIntervalMs;

    private final AtomicLong tickCounter = new AtomicLong(0);
    private final AtomicLong poolBurstCounter = new AtomicLong(0);
    private final AtomicLong slowQueryCounter = new AtomicLong(0);

    public StressScenarioSimulator(JdbcTemplate jdbcTemplate) {
        super("stress-scenario", "stress");
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected long resolveIntervalMs() {
        long base = Math.min(
                poolBurstMs > 0 ? poolBurstMs : Long.MAX_VALUE,
                slowQueryMs > 0 ? slowQueryMs : Long.MAX_VALUE);
        return base == Long.MAX_VALUE ? 60_000L : Math.max(1000L, base / 6);
    }

    @Override
    protected void tick() {
        long n = tickCounter.incrementAndGet();
        long stepMs = intervalMsActual();
        if (poolBurstMs > 0 && n % Math.max(1, poolBurstMs / stepMs) == 0) {
            triggerPoolBurst();
        }
        if (slowQueryMs > 0 && n % Math.max(1, slowQueryMs / stepMs) == 0) {
            triggerSlowQuery();
        }
    }

    private long intervalMsActual() {
        return Math.max(1000L, resolveIntervalMs());
    }

    private void triggerPoolBurst() {
        long burstSeq = poolBurstCounter.incrementAndGet();
        int waves = Math.max(1, poolBurstWaves);
        long gapMs = Math.max(50, poolBurstWaveGapMs);
        log.warn("[kxj: 压测-连接池打满] 启动 waves={} concurrent/wave={} hold={}s gap={}ms seq={}",
                waves, poolConcurrent, poolHoldSeconds, gapMs, burstSeq);
        for (int w = 0; w < waves; w++) {
            final int waveIdx = w;
            ExecutorService burstExec = Executors.newFixedThreadPool(poolConcurrent, r -> {
                Thread t = new Thread(r, "sim-pool-burst-" + burstSeq + "-w" + waveIdx);
                t.setDaemon(true);
                return t;
            });
            CountDownLatch done = new CountDownLatch(poolConcurrent);
            for (int i = 0; i < poolConcurrent; i++) {
                final int idx = i;
                burstExec.submit(() -> {
                    try {
                        jdbcTemplate.execute("CALL SLEEP(" + poolHoldSeconds + ")");
                    } catch (Exception e) {
                        log.debug("[kxj: 压测异常] seq={} wave={} idx={} err={}",
                                burstSeq, waveIdx, idx, e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }
            try {
                done.await(poolHoldSeconds + 5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                burstExec.shutdownNow();
            }
            log.warn("[kxj: 压测-连接池打满] 完成 wave={}/{} seq={}",
                    waveIdx + 1, waves, burstSeq);
            if (w < waves - 1) {
                try {
                    Thread.sleep(gapMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void triggerSlowQuery() {
        long seq = slowQueryCounter.incrementAndGet();
        log.warn("[kxj: 压测-慢查询] 注入 count={} duration={}ms interval={}ms seq={}",
                slowQueryCount, slowQueryDurationMs, slowQueryIntervalMs, seq);
        for (int i = 0; i < slowQueryCount; i++) {
            try {
                jdbcTemplate.queryForList("SELECT SLEEP(" + slowQueryDurationMs + ") FROM products");
            } catch (Exception e) {
                log.debug("[kxj: 慢查询异常] seq={} idx={} err={}", seq, i, e.getMessage());
            }
            try {
                Thread.sleep(slowQueryIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
