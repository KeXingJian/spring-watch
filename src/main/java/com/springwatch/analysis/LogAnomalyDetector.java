package com.springwatch.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogAnomalyDetector {

    private static final String KEY_LAST_RATE = "log:errorRate:";
    private static final String KEY_KNOWN_PATTERNS = "log:knownPatterns:";

    private final StringRedisTemplate redis;

    @Value("${spring-watch.log.anomaly.rate-ttl-seconds:600}")
    private long rateTtlSeconds;

    @Value("${spring-watch.log.anomaly.pattern-ttl-hours:168}")
    private long patternTtlHours;

    @Value("${spring-watch.log.anomaly.min-base-rate:0.01}")
    private double minBaseRate;

    /**
     * kxj: 错误率突增检测-当前窗口rate与上一窗口对比,倍数>=multiplier视为突增
     */
    public boolean isErrorRateSpiking(long appid, double currentRate, double multiplier) {
        log.debug("[spring-watch: LogAnomalyDetector isErrorRateSpiking - appid={}, current={}, multiplier={}]", appid, currentRate, multiplier);
        String key = KEY_LAST_RATE + appid;
        Double lastRate = parseDouble(redis.opsForValue().get(key));
        redis.opsForValue().set(key, String.valueOf(currentRate), Duration.ofSeconds(rateTtlSeconds));
        if (lastRate == null || lastRate < minBaseRate) {
            log.debug("[spring-watch: LogAnomalyDetector 跳过突增判断 - appid={}, lastRate={}, minBaseRate={}", appid, lastRate, minBaseRate);
            return false;
        }
        boolean spiking = currentRate / lastRate >= multiplier;
        if (spiking) {
            log.info("[spring-watch: LogAnomalyDetector 错误率突增 - appid={}, current={}, last={}, multiplier={}]",
                    appid, currentRate, lastRate, multiplier);
        } else {
            log.debug("[spring-watch: LogAnomalyDetector 错误率未突增 - appid={}, current={}, last={}, ratio={}",
                    appid, currentRate, lastRate, currentRate / lastRate);
        }
        return spiking;
    }

    /**
     * kxj: 新模式发现-fingerprint首次出现返回true(Redis Set记录已知模式)
     */
    public boolean isNewPattern(long appid, String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            log.debug("[spring-watch: LogAnomalyDetector isNewPattern fingerprint为空, 跳过 - appid={}", appid);
            return false;
        }
        String key = KEY_KNOWN_PATTERNS + appid;
        Long added = redis.opsForSet().add(key, fingerprint);
        redis.expire(key, Duration.ofHours(patternTtlHours));
        boolean isNew = added != null && added > 0;
        if (isNew) {
            log.info("[spring-watch: LogAnomalyDetector 新模式 - appid={}, fingerprint={}]", appid, fingerprint);
        } else {
            log.debug("[spring-watch: LogAnomalyDetector 模式已存在 - appid={}, fingerprint={}", appid, fingerprint);
        }
        return isNew;
    }

    private Double parseDouble(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
