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

    @Value("${mock.sim.stress-pool-burst-ms:60000}")
    private long poolBurstMs;

    @Value("${mock.sim.stress-slow-query-ms:90000}")
    private long slowQueryMs;

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
        return base == Long.MAX_VALUE ? 60_000L : Math.max(1000L, base);
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
        int concurrent = 30;
        long holdSeconds = 3;
        ExecutorService burstExec = Executors.newFixedThreadPool(concurrent, r -> {
            Thread t = new Thread(r, "sim-pool-burst-" + burstSeq);
            t.setDaemon(true);
            return t;
        });
        log.warn("[kxj: 压测-连接池打满] 启动 concurrent={} hold={}s seq={}",
                concurrent, holdSeconds, burstSeq);
        CountDownLatch done = new CountDownLatch(concurrent);
        for (int i = 0; i < concurrent; i++) {
            final int idx = i;
            burstExec.submit(() -> {
                try {
                    jdbcTemplate.execute("CALL SLEEP(" + holdSeconds + ")");
                } catch (Exception e) {
                    log.debug("[kxj: 压测异常] idx={} err={}", idx, e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await(holdSeconds + 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            burstExec.shutdownNow();
            log.warn("[kxj: 压测-连接池打满] 完成 seq={}", burstSeq);
        }
    }

    private void triggerSlowQuery() {
        long seq = slowQueryCounter.incrementAndGet();
        long durationMs = 2000;
        log.warn("[kxj: 压测-慢查询] 注入 duration={}ms seq={}", durationMs, seq);
        try {
            jdbcTemplate.queryForList("SELECT SLEEP(" + durationMs + ") FROM products");
        } catch (Exception e) {
            log.debug("[kxj: 慢查询异常] seq={} err={}", seq, e.getMessage());
        }
    }
}
