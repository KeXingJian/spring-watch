package com.springwatch.alerter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertStateStore {

    private static final String KEY_PREFIX = "alert:state:";
    private static final String FIELD_STATE = "state";
    private static final String FIELD_FIRST_BREACH_AT = "firstBreachAt";
    private static final String FIELD_LAST_FIRED_AT = "lastFiredAt";
    private static final String FIELD_TRIGGER_COUNT = "triggerCount";

    private final StringRedisTemplate redis;

    @Value("${spring-watch.alert.state-store.ttl-hours:24}")
    private long ttlHours;

    public AlertState getState(Long ruleId, Long appid) {
        Object v = redis.opsForHash().get(key(ruleId, appid), FIELD_STATE);
        if (v == null) {
            return AlertState.IDLE;
        }
        try {
            return AlertState.valueOf(v.toString());
        } catch (Exception e) {
            log.warn("[Alerter] 状态值非法, 重置为IDLE - ruleId={}, appid={}, value={}", ruleId, appid, v);
            return AlertState.IDLE;
        }
    }

    public Instant getFirstBreachAt(Long ruleId, Long appid) {
        Object v = redis.opsForHash().get(key(ruleId, appid), FIELD_FIRST_BREACH_AT);
        return v == null ? null : Instant.ofEpochMilli(Long.parseLong(v.toString()));
    }

    public Instant getLastFiredAt(Long ruleId, Long appid) {
        Object v = redis.opsForHash().get(key(ruleId, appid), FIELD_LAST_FIRED_AT);
        return v == null ? null : Instant.ofEpochMilli(Long.parseLong(v.toString()));
    }

    public long incrementTriggerCount(Long ruleId, Long appid) {
        String key = key(ruleId, appid);
        Long count = redis.opsForHash().increment(key, FIELD_TRIGGER_COUNT, 1L);
        redis.expire(key, Duration.ofHours(ttlHours));
        long result = count == null ? 0L : count;
        log.debug("[Alerter] 触发次数自增 - ruleId={}, appid={}, count={}", ruleId, appid, result);
        return result;
    }

    public void clearTriggerCount(Long ruleId, Long appid) {
        redis.opsForHash().delete(key(ruleId, appid), FIELD_TRIGGER_COUNT);
    }

    public void setState(Long ruleId, Long appid, AlertState state, Instant firstBreachAt, Instant lastFiredAt) {
        String key = key(ruleId, appid);
        Map<String, String> map = new HashMap<>();
        map.put(FIELD_STATE, state.name());
        if (firstBreachAt != null) {
            map.put(FIELD_FIRST_BREACH_AT, String.valueOf(firstBreachAt.toEpochMilli()));
        } else {
            redis.opsForHash().delete(key, FIELD_FIRST_BREACH_AT);
        }
        if (lastFiredAt != null) {
            map.put(FIELD_LAST_FIRED_AT, String.valueOf(lastFiredAt.toEpochMilli()));
        } else {
            redis.opsForHash().delete(key, FIELD_LAST_FIRED_AT);
        }
        if (!map.isEmpty()) {
            redis.opsForHash().putAll(key, map);
        }
        redis.expire(key, Duration.ofHours(ttlHours));
        log.debug("[Alerter] 状态变更 - ruleId={}, appid={}, state={}, firstBreachAt={}, lastFiredAt={}",
                ruleId, appid, state, firstBreachAt, lastFiredAt);
    }

    public void clear(Long ruleId, Long appid) {
        redis.delete(key(ruleId, appid));
        log.debug("[Alerter] 状态清除 - ruleId={}, appid={}", ruleId, appid);
    }

    private String key(Long ruleId, Long appid) {
        return KEY_PREFIX + ruleId + ":" + appid;
    }
}
