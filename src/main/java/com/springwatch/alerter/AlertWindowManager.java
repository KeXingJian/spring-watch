package com.springwatch.alerter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertWindowManager {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "alert:window:";
    private static final long WINDOW_MINUTES = 10;

    public boolean isAlreadyFired(Long ruleId, Instant timestamp) {
        String key = KEY_PREFIX + ruleId;
        String score = String.valueOf(timestamp.toEpochMilli());
        Double existing = redisTemplate.opsForZSet().score(key, score);
        return existing != null;
    }

    public void recordFire(Long ruleId, Instant timestamp) {
        String key = KEY_PREFIX + ruleId;
        String score = String.valueOf(timestamp.toEpochMilli());
        redisTemplate.opsForZSet().add(key, score, timestamp.toEpochMilli());
        redisTemplate.expire(key, Duration.ofMinutes(WINDOW_MINUTES));
        log.debug("[spring-watch: 告警窗口记录 - ruleId={}, timestamp={}]", ruleId, timestamp);
    }
}