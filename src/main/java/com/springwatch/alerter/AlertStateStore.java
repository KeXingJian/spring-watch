package com.springwatch.alerter;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
    private static final String FIELD_LAST_VALUE = "lastValue";
    private static final String FIELD_LAST_METRIC = "lastMetric";
    private static final String FIELD_LAST_TAGS = "lastTags";

    /**
     * kxj: PENDING→FIRING 原子CAS脚本,只把当前状态=PENDING的key原子切到FIRING并写时间戳,避免多线程并发fire
     * KEYS[1]=state key;ARGV[1]=firstBreachAt(空串保留);ARGV[2]=lastFiredAt(空串保留);ARGV[3]=TTL秒
     * 返回 1=抢到FIRING权 0=状态非PENDING被拒
     */
    private static final String LUA_TRY_FIRE = """
            local state = redis.call('HGET', KEYS[1], 'state')
            if state ~= 'PENDING' then
                return 0
            end
            redis.call('HSET', KEYS[1], 'state', 'FIRING')
            if ARGV[1] ~= '' then
                redis.call('HSET', KEYS[1], 'firstBreachAt', ARGV[1])
            end
            if ARGV[2] ~= '' then
                redis.call('HSET', KEYS[1], 'lastFiredAt', ARGV[2])
            end
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return 1
            """;

    /**
     * kxj: FIRING→RESOLVED 原子CAS脚本,只把当前状态=FIRING的key原子切到RESOLVED并清时间戳,避免多线程并发resolve
     * KEYS[1]=state key;ARGV[1]=TTL秒
     * 返回 1=抢到RESOLVED权 0=状态非FIRING被拒
     */
    private static final String LUA_TRY_RESOLVE = """
            local state = redis.call('HGET', KEYS[1], 'state')
            if state ~= 'FIRING' then
                return 0
            end
            redis.call('HSET', KEYS[1], 'state', 'RESOLVED')
            redis.call('HDEL', KEYS[1], 'firstBreachAt', 'lastFiredAt')
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            return 1
            """;

    private final StringRedisTemplate redis;

    @Value("${spring-watch.alert.state-store.ttl-hours:24}")
    private long ttlHours;

    private DefaultRedisScript<Long> tryFireScript;
    private DefaultRedisScript<Long> tryResolveScript;

    @PostConstruct
    void initScript() {
        this.tryFireScript = new DefaultRedisScript<>(LUA_TRY_FIRE, Long.class);
        this.tryResolveScript = new DefaultRedisScript<>(LUA_TRY_RESOLVE, Long.class);
    }

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

    /**
     * kxj: 记录最近一次事件快照-扫描器兜底触发时复用
     */
    public void recordLastEvent(Long ruleId, Long appid, Double value, String metricName, String tagsJson) {
        String k = key(ruleId, appid);
        Map<String, String> map = new HashMap<>();
        if (value != null) {
            map.put(FIELD_LAST_VALUE, String.valueOf(value));
        }
        if (metricName != null) {
            map.put(FIELD_LAST_METRIC, metricName);
        }
        if (tagsJson != null) {
            map.put(FIELD_LAST_TAGS, tagsJson);
        }
        if (!map.isEmpty()) {
            redis.opsForHash().putAll(k, map);
        }
    }

    public String getLastValue(Long ruleId, Long appid) {
        Object v = redis.opsForHash().get(key(ruleId, appid), FIELD_LAST_VALUE);
        return v == null ? null : v.toString();
    }

    public String getLastMetric(Long ruleId, Long appid) {
        Object v = redis.opsForHash().get(key(ruleId, appid), FIELD_LAST_METRIC);
        return v == null ? null : v.toString();
    }

    public String getLastTagsJson(Long ruleId, Long appid) {
        Object v = redis.opsForHash().get(key(ruleId, appid), FIELD_LAST_TAGS);
        return v == null ? null : v.toString();
    }

    public void setState(Long ruleId, Long appid, AlertState state, Instant firstBreachAt, Instant lastFiredAt) {
        String key = key(ruleId, appid);
        Map<String, String> map = new HashMap<>();
        map.put(FIELD_STATE, state.name());
        if (firstBreachAt != null) {
            map.put(FIELD_FIRST_BREACH_AT, String.valueOf(firstBreachAt.toEpochMilli()));
        }
        if (lastFiredAt != null) {
            map.put(FIELD_LAST_FIRED_AT, String.valueOf(lastFiredAt.toEpochMilli()));
        }
        final boolean clearFirstBreach = firstBreachAt == null;
        final boolean clearLastFired = lastFiredAt == null;
        final String finalKey = key;
        redis.execute(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"unchecked"})
            public Object execute(org.springframework.data.redis.core.@NonNull RedisOperations operations) {
                operations.multi();
                if (clearFirstBreach) {
                    operations.opsForHash().delete(finalKey, FIELD_FIRST_BREACH_AT);
                }
                if (clearLastFired) {
                    operations.opsForHash().delete(finalKey, FIELD_LAST_FIRED_AT);
                }
                if (!map.isEmpty()) {
                    operations.opsForHash().putAll(finalKey, map);
                }
                operations.expire(finalKey, Duration.ofHours(ttlHours));
                return operations.exec();
            }
        });
        log.debug("[Alerter] 状态变更 - ruleId={}, appid={}, state={}, firstBreachAt={}, lastFiredAt={}",
                ruleId, appid, state, firstBreachAt, lastFiredAt);
    }

    public void clear(Long ruleId, Long appid) {
        redis.delete(key(ruleId, appid));
        log.debug("[Alerter] 状态清除 - ruleId={}, appid={}", ruleId, appid);
    }

    /**
     * kxj: 原子抢占FIRING-只有当前状态=PENDING才切到FIRING并写时间戳,多线程只有第一个拿到true
     */
    public boolean tryFire(Long ruleId, Long appid, Instant firstBreachAt, Instant lastFiredAt) {
        String key = key(ruleId, appid);
        String firstBreach = firstBreachAt == null ? "" : String.valueOf(firstBreachAt.toEpochMilli());
        String lastFired = lastFiredAt == null ? "" : String.valueOf(lastFiredAt.toEpochMilli());
        long ttlSeconds = ttlHours * 3600L;
        Long result = redis.execute(tryFireScript,
                Collections.singletonList(key),
                firstBreach, lastFired, String.valueOf(ttlSeconds));
        boolean fired = result != null && result == 1L;
        if (fired) {
            log.info("[Alerter] CAS抢占FIRING成功 - ruleId={}, appid={}", ruleId, appid);
        } else {
            log.debug("[Alerter] CAS抢占FIRING失败, 状态非PENDING - ruleId={}, appid={}", ruleId, appid);
        }
        return fired;
    }

    /**
     * kxj: 原子抢占RESOLVED-只有当前状态=FIRING才切到RESOLVED并清时间戳,多线程只有第一个拿到true
     */
    public boolean tryResolve(Long ruleId, Long appid) {
        String key = key(ruleId, appid);
        long ttlSeconds = ttlHours * 3600L;
        Long result = redis.execute(tryResolveScript,
                Collections.singletonList(key),
                String.valueOf(ttlSeconds));
        boolean resolved = result != null && result == 1L;
        if (resolved) {
            log.info("[Alerter] CAS抢占RESOLVED成功 - ruleId={}, appid={}", ruleId, appid);
        } else {
            log.debug("[Alerter] CAS抢占RESOLVED失败, 状态非FIRING - ruleId={}, appid={}", ruleId, appid);
        }
        return resolved;
    }

    /**
     * kxj: PENDING扫描器-游标扫所有状态键,过滤PENDING返回 [借鉴 HertzBeat PeriodicAlertRuleScheduler]
     */
    public List<PendingEntry> scanPending(long scanCount) {
        List<PendingEntry> result = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(scanCount).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                int firstColon = KEY_PREFIX.length();
                int secondColon = key.indexOf(':', firstColon);
                if (secondColon < 0) {
                    continue;
                }
                Long ruleId;
                Long appid;
                try {
                    ruleId = Long.parseLong(key.substring(firstColon, secondColon));
                    appid = Long.parseLong(key.substring(secondColon + 1));
                } catch (NumberFormatException e) {
                    continue;
                }
                Map<Object, Object> entries = redis.opsForHash().entries(key);
                Object stateVal = entries.get(FIELD_STATE);
                if (stateVal == null || !AlertState.PENDING.name().equals(stateVal.toString())) {
                    continue;
                }
                Object firstBreachVal = entries.get(FIELD_FIRST_BREACH_AT);
                if (firstBreachVal == null) {
                    continue;
                }
                Instant firstBreachAt;
                try {
                    firstBreachAt = Instant.ofEpochMilli(Long.parseLong(firstBreachVal.toString()));
                } catch (NumberFormatException e) {
                    continue;
                }
                long triggerCount = 0L;
                Object countVal = entries.get(FIELD_TRIGGER_COUNT);
                if (countVal != null) {
                    try {
                        triggerCount = Long.parseLong(countVal.toString());
                    } catch (NumberFormatException ignored) {
                    }
                }
                result.add(new PendingEntry(ruleId, appid, firstBreachAt, triggerCount));
            }
        } catch (Exception e) {
            log.warn("[Alerter] 扫PENDING状态失败 - error={}", e.getMessage());
        }
        log.debug("[Alerter] scanPending 完成 - size={}", result.size());
        return result;
    }

    public record PendingEntry(Long ruleId, Long appid, Instant firstBreachAt, long triggerCount) {}

    private String key(Long ruleId, Long appid) {
        return KEY_PREFIX + ruleId + ":" + appid;
    }
}
