package com.mock.test.sim;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@ConditionalOnProperty(name = "mock.sim.enabled", havingValue = "true", matchIfMissing = true)
public class LogBurstSimulator extends BaseSimulator {

    private static final Logger simLog = LoggerFactory.getLogger("com.mock.test.sim.LogBurstSimulator");

    @Value("${mock.sim.log-burst.warn-burst-ms:60000}")
    private long warnBurstMs;

    @Value("${mock.sim.log-burst.warn-count:100}")
    private int warnCount;

    @Value("${mock.sim.log-burst.warn-duration-ms:10000}")
    private long warnDurationMs;

    @Value("${mock.sim.log-burst.error-burst-ms:180000}")
    private long errorBurstMs;

    @Value("${mock.sim.log-burst.error-count:60}")
    private int errorCount;

    @Value("${mock.sim.log-burst.error-duration-ms:10000}")
    private long errorDurationMs;

    @Value("${mock.sim.log-burst.continuous-error-ms:15000}")
    private long continuousErrorMs;

    @Value("${mock.sim.log-burst.continuous-error-rate:0.3}")
    private double continuousErrorRate;

    private final AtomicLong tickCounter = new AtomicLong(0);
    private final AtomicLong warnSeq = new AtomicLong(0);
    private final AtomicLong errorSeq = new AtomicLong(0);
    private final AtomicLong continuousSeq = new AtomicLong(0);

    public LogBurstSimulator() {
        super("log-burst", "logburst");
    }

    @Override
    protected long resolveIntervalMs() {
        long base = Math.min(
                warnBurstMs > 0 ? warnBurstMs : Long.MAX_VALUE,
                Math.min(
                        errorBurstMs > 0 ? errorBurstMs : Long.MAX_VALUE,
                        continuousErrorMs > 0 ? continuousErrorMs : Long.MAX_VALUE));
        return base == Long.MAX_VALUE ? 120_000L : Math.max(1000L, base / 6);
    }

    @Override
    protected void tick() {
        long n = tickCounter.incrementAndGet();
        long stepMs = Math.max(1000L, resolveIntervalMs());
        if (warnBurstMs > 0 && n % Math.max(1, warnBurstMs / stepMs) == 0) {
            triggerWarnBurst();
        }
        if (errorBurstMs > 0 && n % Math.max(1, errorBurstMs / stepMs) == 0) {
            triggerErrorBurst();
        }
        if (continuousErrorMs > 0 && n % Math.max(1, continuousErrorMs / stepMs) == 0) {
            triggerContinuousError();
        }
    }

    private void triggerWarnBurst() {
        long seq = warnSeq.incrementAndGet();
        long perDelay = warnDurationMs / Math.max(1, warnCount);
        log.warn("[kxj: 日志突增-已知模式] 启动 WARN 突增 count={} duration={}ms seq={}",
                warnCount, warnDurationMs, seq);
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sim-warn-burst-" + seq);
            t.setDaemon(true);
            return t;
        });
        exec.submit(() -> {
            for (int i = 0; i < warnCount; i++) {
                simLog.warn("[kxj: 库存不足] productId={} requested={} available=0 - 回滚订单 seq={}",
                        1 + (i % 5), 1 + i, seq);
                try {
                    Thread.sleep(perDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.warn("[kxj: 日志突增-已知模式] 完成 seq={}", seq);
        });
        exec.shutdown();
    }

    private void triggerErrorBurst() {
        long seq = errorSeq.incrementAndGet();
        long perDelay = errorDurationMs / Math.max(1, errorCount);
        log.warn("[kxj: 日志突增-异常模式] 启动 ERROR 突增 count={} duration={}ms seq={}",
                errorCount, errorDurationMs, seq);
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sim-error-burst-" + seq);
            t.setDaemon(true);
            return t;
        });
        String[] templates = {
                "[kxj: 模拟异常路径] type=NullPointerException path=/api/orders/{id} traceId=auto-{n} - 用户不存在 seq={seq}",
                "[kxj: 慢查询告警] query=SELECT * FROM huge_table duration={ms}ms userId={id} - 超过阈值 seq={seq}",
                "[kxj: 下游超时] downstream=payment-svc latency={ms}ms orderId={id} - 重试1次 seq={seq}",
                "[kxj: 鉴权失败] userId={id} reason=token-expired path=/api/orders/{id} - 拒绝访问 seq={seq}",
                "[kxj: 库存锁定失败] productId={id} lockHolder=user-{n} - 等待超时 seq={seq}",
                "[kxj: 数据库连接超时] pool=primary acquireMs={ms} - 触发熔断 seq={seq}",
                "[kxj: 限流触发] userId={id} limit=100 actual={ms} - 返回429 seq={seq}"
        };
        exec.submit(() -> {
            for (int i = 0; i < errorCount; i++) {
                String tpl = templates[i % templates.length]
                        .replace("{id}", String.valueOf(1 + (i % 3)))
                        .replace("{n}", String.valueOf(i))
                        .replace("{ms}", String.valueOf(800 + i * 37))
                        .replace("{seq}", String.valueOf(seq));
                simLog.error(tpl);
                try {
                    Thread.sleep(perDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.warn("[kxj: 日志突增-异常模式] 完成 seq={}", seq);
        });
        exec.shutdown();
    }

    private void triggerContinuousError() {
        long seq = continuousSeq.incrementAndGet();
        log.info("[kxj: 持续错误流] 启动 duration={}ms rate={} seq={}",
                continuousErrorMs, continuousErrorRate, seq);
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sim-continuous-err-" + seq);
            t.setDaemon(true);
            return t;
        });
        exec.submit(() -> {
            long deadline = System.currentTimeMillis() + continuousErrorMs;
            long emitted = 0;
            while (System.currentTimeMillis() < deadline) {
                if (ThreadLocalRandom.current().nextDouble() < continuousErrorRate) {
                    simLog.error("[kxj: 持续错误] seq={} idx={} message=transient-failure", seq, emitted++);
                } else {
                    simLog.info("[kxj: 持续正常] seq={} idx={}", seq, emitted++);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("[kxj: 持续错误流] 完成 seq={} emitted={}", seq, emitted);
        });
        exec.shutdown();
    }
}
