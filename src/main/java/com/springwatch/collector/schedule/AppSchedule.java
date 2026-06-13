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
        int jitterPercent
) {

    private static final long MIN_DELAY_MS = 1_000L;
    private static final int DEFAULT_INTERVAL_SECONDS = 15;

    public static AppSchedule from(MonitorApp app, int jitterPercent) {
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
                log.warn("[spring-watch: 调度解析降级 - appid={}, reason=cron为空, fallback=INTERVAL, intervalSec={}]",
                        appid, intervalSec);
                type = AppScheduleType.INTERVAL;
            } else {
                try {
                    cron = CronExpression.parse(expr.trim());
                    log.info("[spring-watch: 调度解析成功 - appid={}, type=CRON, expr={}, intervalMs(默认)={}]",
                            appid, expr.trim(), intervalMs);
                } catch (Exception e) {
                    log.warn("[spring-watch: 调度解析降级 - appid={}, reason=cron格式非法, expr={}, error={}, fallback=INTERVAL, intervalSec={}]",
                            appid, expr, e.getMessage(), intervalSec);
                    type = AppScheduleType.INTERVAL;
                }
            }
        } else {
            log.debug("[spring-watch: 调度解析 - appid={}, type=INTERVAL, intervalSec={}, intervalMs={}]",
                    appid, intervalSec, intervalMs);
        }
        return new AppSchedule(appid, type, intervalMs, cron, Math.max(0, jitterPercent));
    }

    public long firstDelayMs(Instant now) {
        if (type == AppScheduleType.CRON && cron != null) {
            Instant next = cron.next(now);
            if (next == null) {
                long fallback = jitterIntervalMs(intervalMs, jitterPercent);
                log.warn("[spring-watch: cron无下次触发 - appid={}, fallback=jitter, delayMs={}]", appid, fallback);
                return fallback;
            }
            long delay = Math.max(0L, Duration.between(now, next).toMillis());
            log.info("[spring-watch: 首次延迟计算 - appid={}, type=CRON, now={}, nextFire={}, delayMs={}]",
                    appid, now, next, delay);
            return delay;
        }
        long jittered = jitterIntervalMs(intervalMs, jitterPercent);
        long firstDelay = jittered / 2;
        log.info("[spring-watch: 首次延迟计算 - appid={}, type=INTERVAL, baseMs={}, jitteredMs={}, firstDelayMs={}]",
                appid, intervalMs, jittered, firstDelay);
        return firstDelay;
    }

    public long nextIntervalMs(Instant now) {
        if (type == AppScheduleType.CRON && cron != null) {
            Instant next = cron.next(now);
            if (next == null) {
                log.warn("[spring-watch: cron无下次触发 - appid={}, fallback=baseInterval", appid);
                return Math.max(MIN_DELAY_MS, intervalMs);
            }
            long delay = Math.max(MIN_DELAY_MS, Duration.between(now, next).toMillis());
            log.debug("[spring-watch: cron下次延迟 - appid={}, now={}, nextFire={}, delayMs={}]",
                    appid, now, next, delay);
            return delay;
        }
        long jittered = Math.max(MIN_DELAY_MS, jitterIntervalMs(intervalMs, jitterPercent));
        log.trace("[spring-watch: interval下次延迟 - appid={}, baseMs={}, jitteredMs={}]",
                appid, intervalMs, jittered);
        return jittered;
    }

    public long baseIntervalMs() {
        return type == AppScheduleType.CRON ? MIN_DELAY_MS : intervalMs;
    }

    private static long jitterIntervalMs(long base, int percent) {
        if (percent <= 0) {
            log.trace("[spring-watch: jitter禁用 - baseMs={}, percent={}]", base, percent);
            return base;
        }
        int range = (int) (base * (long) percent / 100L);
        if (range <= 0) {
            return base;
        }
        int delta = ThreadLocalRandom.current().nextInt(-range, range + 1);
        long result = base + delta;
        log.trace("[spring-watch: jitter计算 - baseMs={}, percent={}, range={}, delta={}, resultMs={}]",
                base, percent, range, delta, result);
        return result;
    }

    private static AppScheduleType parseType(String s) {
        if (s == null || s.isBlank()) {
            log.trace("[spring-watch: scheduleType为空 - fallback=INTERVAL]");
            return AppScheduleType.INTERVAL;
        }
        try {
            return AppScheduleType.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("[spring-watch: scheduleType非法 - value={}, fallback=INTERVAL]", s);
            return AppScheduleType.INTERVAL;
        }
    }
}
