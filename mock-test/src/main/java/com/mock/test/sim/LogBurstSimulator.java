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

    @Value("${mock.sim.log-warn-burst-ms:120000}")
    private long warnBurstMs;

    @Value("${mock.sim.log-error-burst-ms:300000}")
    private long errorBurstMs;

    private final AtomicLong tickCounter = new AtomicLong(0);
    private final AtomicLong warnSeq = new AtomicLong(0);
    private final AtomicLong errorSeq = new AtomicLong(0);

    public LogBurstSimulator() {
        super("log-burst", "logburst");
    }

    @Override
    protected long resolveIntervalMs() {
        long base = Math.min(
                warnBurstMs > 0 ? warnBurstMs : Long.MAX_VALUE,
                errorBurstMs > 0 ? errorBurstMs : Long.MAX_VALUE);
        return base == Long.MAX_VALUE ? 120_000L : Math.max(1000L, base);
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
    }

    private void triggerWarnBurst() {
        long seq = warnSeq.incrementAndGet();
        int count = 50;
        long durationMs = 5000;
        long perDelay = durationMs / count;
        log.warn("[kxj: 日志突增-已知模式] 启动 WARN 突增 count={} duration={}ms seq={}",
                count, durationMs, seq);
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sim-warn-burst-" + seq);
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < count; i++) {
            try {
                Thread.sleep(perDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            simLog.warn("[kxj: 库存不足] productId={} requested={} available=0 - 回滚订单 seq={}",
                    1 + (i % 5), 1 + i, seq);
        }
        exec.shutdownNow();
        log.warn("[kxj: 日志突增-已知模式] 完成 seq={}", seq);
    }

    private void triggerErrorBurst() {
        long seq = errorSeq.incrementAndGet();
        int count = 25;
        long durationMs = 5000;
        long perDelay = durationMs / count;
        log.warn("[kxj: 日志突增-异常模式] 启动 ERROR 突增 count={} duration={}ms seq={}",
                count, durationMs, seq);
        String[] templates = {
                "[kxj: 模拟异常路径] type=NullPointerException path=/api/orders/{id} traceId=auto-{n} - 用户不存在 seq={seq}",
                "[kxj: 慢查询告警] query=SELECT * FROM huge_table duration={ms}ms userId={id} - 超过阈值 seq={seq}",
                "[kxj: 下游超时] downstream=payment-svc latency={ms}ms orderId={id} - 重试1次 seq={seq}",
                "[kxj: 鉴权失败] userId={id} reason=token-expired path=/api/orders/{id} - 拒绝访问 seq={seq}",
                "[kxj: 库存锁定失败] productId={id} lockHolder=user-{n} - 等待超时 seq={seq}"
        };
        for (int i = 0; i < count; i++) {
            try {
                Thread.sleep(perDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            String tpl = templates[i % templates.length]
                    .replace("{id}", String.valueOf(1 + (i % 3)))
                    .replace("{n}", String.valueOf(i))
                    .replace("{ms}", String.valueOf(800 + i * 37))
                    .replace("{seq}", String.valueOf(seq));
            simLog.error(tpl);
        }
        log.warn("[kxj: 日志突增-异常模式] 完成 seq={}", seq);
    }
}
