package com.springwatch.ingest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogDedupService {

    private static final String KEY_PREFIX = "log:dedup:";
    private static final String COUNT_PREFIX = "log:dedup:count:";

    private final StringRedisTemplate redis;

    @Value("${spring-watch.log.dedup.window-seconds:60}")
    private long windowSeconds;

    @Value("${spring-watch.log.dedup.count-ttl-seconds:3600}")
    private long countTtlSeconds;

    /**
     * kxj: 滑动窗口去重-同appid+fingerprint窗口内只保留首条样本,其余累加计数
     */
    public boolean shouldKeep(long appid, String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return true;
        }
        String key = KEY_PREFIX + appid + ":" + fingerprint;
        Boolean first = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(windowSeconds));
        if (Boolean.TRUE.equals(first)) {
            return true;
        }
        String countKey = COUNT_PREFIX + appid + ":" + fingerprint;
        redis.opsForValue().increment(countKey);
        redis.expire(countKey, Duration.ofSeconds(countTtlSeconds));
        return false;
    }

    public long getDedupCount(long appid, String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return 0L;
        }
        String v = redis.opsForValue().get(COUNT_PREFIX + appid + ":" + fingerprint);
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
