package com.springwatch.collector.schedule;

import com.springwatch.model.entity.MonitorApp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public record AppSchedule(
        Long appid,
        AppScheduleType type,
        long intervalMs,
        CronExpression cron,
        int jitterPercent,
        int firstDelaySpreadMultiplier
) {

    private static final long MIN_DELAY_MS = 1_000L;
    private static final int DEFAULT_INTERVAL_SECONDS = 15;

    public static AppSchedule from(MonitorApp app, int jitterPercent, int firstDelaySpreadMultiplier) {
        Long appid = app.getAppid();
        int intervalSec = app.getScrapeInterval() == null || app.getScrapeInterval() < 1
                ? DEFAULT_INTERVAL_SECONDS
                : app.getScrapeInterval();
        long intervalMs = intervalSec * 1000L;
        AppScheduleType type = parseType(app.getScheduleType());
        CronExpression cron = null;
        if (type == AppScheduleType.CRON) {
            String expr = app.getCronExpression();
            if (expr == null || expr.isBlank()) {
                log.warn("[kxj: 调度解析降级 - appid={}, reason=cron为空, fallback=INTERVAL, intervalSec={}]",
                        appid, intervalSec);
                type = AppScheduleType.INTERVAL;
            } else {
                try {
                    cron = CronExpression.parse(expr.trim());
                    log.info("[kxj: 调度解析成功 - appid={}, type=CRON, expr={}, intervalMs(默认)={}]",
                            appid, expr.trim(), intervalMs);
                } catch (Exception e) {
                    log.warn("[kxj: 调度解析降级 - appid={}, reason=cron格式非法, expr={}, error={}, fallback=INTERVAL, intervalSec={}]",
                            appid, expr, e.getMessage(), intervalSec);
                    type = AppScheduleType.INTERVAL;
                }
            }
        } else {
            log.debug("[kxj: 调度解析 - appid={}, type=INTERVAL, intervalSec={}, intervalMs={}]",
                    appid, intervalSec, intervalMs);
        }
        return new AppSchedule(appid, type, intervalMs, cron,
                Math.max(0, jitterPercent), Math.max(1, firstDelaySpreadMultiplier));
    }

    public long firstDelayMs(Instant now) {
        if (type == AppScheduleType.CRON && cron != null) {
            Instant next = cron.next(now);
            if (next == null) {
                long fallback = jitterIntervalMs(intervalMs, jitterPercent);
                log.warn("[kxj: cron无下次触发 - appid={}, fallback=jitter, delayMs={}]", appid, fallback);
                return fallback;
            }
            long delay = Math.max(0L, Duration.between(now, next).toMillis());
            log.info("[kxj: 首次延迟计算 - appid={}, type=CRON, now={}, nextFire={}, delayMs={}]",
                    appid, now, next, delay);
            return delay;
        }
        long firstDelay = firstDelayAntiHerd(intervalMs, jitterPercent, firstDelaySpreadMultiplier);
        log.info("[kxj: 首次延迟防雷鸣群羊 - appid={}, type=INTERVAL, intervalMs={}, spreadWindowMs={}({}x), jitterPercent={}%, firstDelayMs={}]",
                appid, intervalMs, intervalMs * firstDelaySpreadMultiplier, firstDelaySpreadMultiplier, jitterPercent, firstDelay);
        return firstDelay;
    }

    /**
     * kxj: 防雷鸣群羊(thundering herd) - 启动时 N 个 app 的首次拉取分摊到 spreadWindowMs 窗口
     * 旧实现: 500 app 集中在 6~9s 3s 窗口,瞬间并发 200 HTTP
     * 中间实现: appid 哈希 % intervalMs → [0, 15s) 窗口,500 app 分散到 15s (33/s, 仍高于 global=80 吞吐能力 16/s)
     * 当前实现: 窗口 = intervalMs × firstDelaySpreadMultiplier,默认 2x = [0, 30s) 窗口,500 app 分散到 30s (16.7/s,匹配吞吐)
     *          hash 桶位 + ±jitterRange 随机扰动,后续周期偏移量保持
     */
    private long firstDelayAntiHerd(long interval, int jitterPct, int spreadMultiplier) {
        long spreadWindow = interval * Math.max(1, spreadMultiplier);
        long bucket = Math.floorMod(appid == null ? 0L : appid, spreadWindow);
        int jitterRange = Math.max(50, (int) (interval * (long) jitterPct / 200L));
        long jitter = ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1L);
        return Math.clamp(spreadWindow - 1L, 0L, bucket + jitter);
    }

    public long nextIntervalMs(Instant now) {
        if (type == AppScheduleType.CRON && cron != null) {
            Instant next = cron.next(now);
            if (next == null) {
                log.warn("[kxj: cron无下次触发 - appid={}, fallback=baseInterval", appid);
                return Math.max(MIN_DELAY_MS, intervalMs);
            }
            long delay = Math.max(MIN_DELAY_MS, Duration.between(now, next).toMillis());
            log.debug("[kxj: cron下次延迟 - appid={}, now={}, nextFire={}, delayMs={}]",
                    appid, now, next, delay);
            return delay;
        }
        long jittered = Math.max(MIN_DELAY_MS, jitterIntervalMs(intervalMs, jitterPercent));
        log.trace("[kxj: interval下次延迟 - appid={}, baseMs={}, jitteredMs={}]",
                appid, intervalMs, jittered);
        return jittered;
    }

    public long baseIntervalMs() {
        return type == AppScheduleType.CRON ? MIN_DELAY_MS : intervalMs;
    }

    private static long jitterIntervalMs(long base, int percent) {
        if (percent <= 0) {
            log.trace("[kxj: jitter禁用 - baseMs={}, percent={}]", base, percent);
            return base;
        }
        int range = (int) (base * (long) percent / 100L);
        if (range <= 0) {
            return base;
        }
        int delta = ThreadLocalRandom.current().nextInt(-range, range + 1);
        long result = base + delta;
        log.trace("[kxj: jitter计算 - baseMs={}, percent={}, range={}, delta={}, resultMs={}]",
                base, percent, range, delta, result);
        return result;
    }

    private static AppScheduleType parseType(String s) {
        if (s == null || s.isBlank()) {
            log.trace("[kxj: scheduleType为空 - fallback=INTERVAL]");
            return AppScheduleType.INTERVAL;
        }
        try {
            return AppScheduleType.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("[kxj: scheduleType非法 - value={}, fallback=INTERVAL]", s);
            return AppScheduleType.INTERVAL;
        }
    }
}
