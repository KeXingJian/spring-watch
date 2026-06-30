package com.springwatch.alerter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertStateStore {

    public record RuleAppKey(Long ruleId, Long appid) {
    }

    @Builder(toBuilder = true)
        private record AlertStateData(AlertState state, Instant firstBreachAt, Instant lastFiredAt, long triggerCount,
                                      String lastValue, String lastMetric, String lastTagsJson, Instant expireAt) {
    }

    private static final AlertStateData IDLE_DATA = AlertStateData.builder()
            .state(AlertState.IDLE)
            .expireAt(Instant.EPOCH)
            .build();

    private final MeterRegistry meterRegistry;

    @Value("${spring-watch.alert.state-store.ttl-hours:24}")
    private long ttlHours;

    @Value("${spring-watch.alert.state-store.max-entries:50000}")
    private long maxEntries;

    @Value("${spring-watch.alert.state-store.scan-max-entries:200}")
    private long scanMaxEntries;

    private Cache<RuleAppKey, AlertStateData> stateCache;
    private Counter evictSizeCounter;
    private Counter evictExpiredCounter;
    private Counter casFireHitCounter;
    private Counter casFireMissCounter;
    private Counter casResolveHitCounter;
    private Counter casResolveMissCounter;

    @PostConstruct
    void init() {
        this.stateCache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfter(new Expiry<RuleAppKey, AlertStateData>() {
                    @Override
                    public long expireAfterCreate(@NonNull RuleAppKey key, @NonNull AlertStateData value, long currentTime) {
                        return nanosUntil(value.expireAt(), currentTime);
                    }

                    @Override
                    public long expireAfterUpdate(@NonNull RuleAppKey key, @NonNull AlertStateData value, long currentTime, long currentDuration) {
                        return nanosUntil(value.expireAt(), currentTime);
                    }

                    @Override
                    public long expireAfterRead(@NonNull RuleAppKey key, @NonNull AlertStateData value, long currentTime, long currentDuration) {
                        return nanosUntil(value.expireAt(), currentTime);
                    }

                    private long nanosUntil(Instant target, long currentTimeNanos) {
                        if (target == null) {
                            return Long.MAX_VALUE;
                        }
                        long targetNanos = TimeUnit.SECONDS.toNanos(target.getEpochSecond()) + target.getNano();
                        return targetNanos - currentTimeNanos;
                    }
                })
                .recordStats()
                .removalListener((_, _, cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        evictSizeCounter.increment();
                    } else if (cause == RemovalCause.EXPIRED) {
                        evictExpiredCounter.increment();
                    }
                })
                .build();

        this.evictSizeCounter = Counter.builder("spring.watch.alerter.state.evict_size")
                .description("告警状态因容量上限被 LRU 淘汰次数(趋近 0,超 0 需扩 max-entries)")
                .register(meterRegistry);
        this.evictExpiredCounter = Counter.builder("spring.watch.alerter.state.evict_expired")
                .description("告警状态因 TTL 到期被淘汰次数")
                .register(meterRegistry);
        this.casFireHitCounter = Counter.builder("spring.watch.alerter.state.cas_fire_hit")
                .description("PENDING→FIRING CAS 成功次数")
                .register(meterRegistry);
        this.casFireMissCounter = Counter.builder("spring.watch.alerter.state.cas_fire_miss")
                .description("PENDING→FIRING CAS 失败次数(状态非 PENDING)")
                .register(meterRegistry);
        this.casResolveHitCounter = Counter.builder("spring.watch.alerter.state.cas_resolve_hit")
                .description("FIRING→RESOLVED CAS 成功次数")
                .register(meterRegistry);
        this.casResolveMissCounter = Counter.builder("spring.watch.alerter.state.cas_resolve_miss")
                .description("FIRING→RESOLVED CAS 失败次数(状态非 FIRING)")
                .register(meterRegistry);

        Gauge.builder("spring.watch.alerter.state.cache_size", stateCache, c -> (double) c.estimatedSize())
                .description("告警状态当前缓存 entry 数")
                .register(meterRegistry);
        Gauge.builder("spring.watch.alerter.state.cache_max", this, s -> (double) s.maxEntries)
                .description("告警状态硬上限 entry 数")
                .register(meterRegistry);
    }

    public AlertState getState(Long ruleId, Long appid) {
        AlertStateData data = stateCache.getIfPresent(new RuleAppKey(ruleId, appid));
        if (data == null) {
            return AlertState.IDLE;
        }
        if (Instant.now().isAfter(data.expireAt())) {
            return AlertState.IDLE;
        }
        return data.state();
    }

    public Instant getFirstBreachAt(Long ruleId, Long appid) {
        AlertStateData data = stateCache.getIfPresent(new RuleAppKey(ruleId, appid));
        if (data == null || Instant.now().isAfter(data.expireAt())) {
            return null;
        }
        return data.firstBreachAt();
    }


    public long incrementTriggerCount(Long ruleId, Long appid) {
        RuleAppKey key = new RuleAppKey(ruleId, appid);
        AtomicLong out = new AtomicLong();
        stateCache.asMap().compute(key, (_, existing) -> {
            AlertStateData current = existing != null ? existing : IDLE_DATA;
            long newCount = current.triggerCount() + 1;
            AlertStateData next = current.toBuilder()
                    .triggerCount(newCount)
                    .expireAt(Instant.now().plus(Duration.ofHours(ttlHours)))
                    .build();
            out.set(newCount);
            return next;
        });
        long result = out.get();
        log.debug("[Alerter] 触发次数自增 - ruleId={}, appid={}, count={}", ruleId, appid, result);
        return result;
    }

    public void clearTriggerCount(Long ruleId, Long appid) {
        update(ruleId, appid, current -> current.toBuilder()
                .triggerCount(0L)
                .expireAt(Instant.now().plus(Duration.ofHours(ttlHours)))
                .build());
    }

    public void recordLastEvent(Long ruleId, Long appid, Double value, String metricName, String tagsJson) {
        update(ruleId, appid, current -> {
            var b = current.toBuilder()
                    .expireAt(Instant.now().plus(Duration.ofHours(ttlHours)));
            if (value != null) {
                b.lastValue(String.valueOf(value));
            }
            if (metricName != null) {
                b.lastMetric(metricName);
            }
            if (tagsJson != null) {
                b.lastTagsJson(tagsJson);
            }
            return b.build();
        });
    }

    public String getLastValue(Long ruleId, Long appid) {
        return readField(ruleId, appid, AlertStateData::lastValue);
    }

    public String getLastMetric(Long ruleId, Long appid) {
        return readField(ruleId, appid, AlertStateData::lastMetric);
    }


    public void setState(Long ruleId, Long appid, AlertState state, Instant firstBreachAt, Instant lastFiredAt) {
        update(ruleId, appid, current -> current.toBuilder()
                .state(state)
                .firstBreachAt(firstBreachAt)
                .lastFiredAt(lastFiredAt)
                .expireAt(Instant.now().plus(Duration.ofHours(ttlHours)))
                .build());
        log.debug("[Alerter] 状态变更 - ruleId={}, appid={}, state={}, firstBreachAt={}, lastFiredAt={}",
                ruleId, appid, state, firstBreachAt, lastFiredAt);
    }

    public void clear(Long ruleId, Long appid) {
        stateCache.invalidate(new RuleAppKey(ruleId, appid));
        log.debug("[Alerter] 状态清除 - ruleId={}, appid={}", ruleId, appid);
    }

    public boolean tryFire(Long ruleId, Long appid, Instant firstBreachAt, Instant lastFiredAt) {
        RuleAppKey key = new RuleAppKey(ruleId, appid);
        AtomicBoolean fired = new AtomicBoolean(false);
        stateCache.asMap().compute(key, (_, existing) -> {
            AlertStateData current = existing != null ? existing : IDLE_DATA;
            if (current.state() != AlertState.PENDING) {
                return existing;
            }
            var b = current.toBuilder()
                    .state(AlertState.FIRING)
                    .expireAt(Instant.now().plus(Duration.ofHours(ttlHours)));
            if (firstBreachAt != null) {
                b.firstBreachAt(firstBreachAt);
            }
            if (lastFiredAt != null) {
                b.lastFiredAt(lastFiredAt);
            }
            fired.set(true);
            return b.build();
        });
        boolean result = fired.get();
        if (result) {
            casFireHitCounter.increment();
            log.info("[Alerter] CAS抢占FIRING成功 - ruleId={}, appid={}", ruleId, appid);
        } else {
            casFireMissCounter.increment();
            log.debug("[Alerter] CAS抢占FIRING失败, 状态非PENDING - ruleId={}, appid={}", ruleId, appid);
        }
        return result;
    }

    public boolean tryResolve(Long ruleId, Long appid) {
        RuleAppKey key = new RuleAppKey(ruleId, appid);
        AtomicBoolean resolved = new AtomicBoolean(false);
        stateCache.asMap().compute(key, (_, existing) -> {
            AlertStateData current = existing != null ? existing : IDLE_DATA;
            if (current.state() != AlertState.FIRING) {
                return existing;
            }
            resolved.set(true);
            return current.toBuilder()
                    .state(AlertState.RESOLVED)
                    .firstBreachAt(null)
                    .lastFiredAt(null)
                    .expireAt(Instant.now().plus(Duration.ofHours(ttlHours)))
                    .build();
        });
        boolean result = resolved.get();
        if (result) {
            casResolveHitCounter.increment();
            log.info("[Alerter] CAS抢占RESOLVED成功 - ruleId={}, appid={}", ruleId, appid);
        } else {
            casResolveMissCounter.increment();
            log.debug("[Alerter] CAS抢占RESOLVED失败, 状态非FIRING - ruleId={}, appid={}", ruleId, appid);
        }
        return result;
    }

    public List<PendingEntry> scanPending(long scanCount) {
        long limit = Math.min(scanCount, scanMaxEntries);
        List<PendingEntry> result = new ArrayList<>();
        for (Map.Entry<RuleAppKey, AlertStateData> entry : stateCache.asMap().entrySet()) {
            if (result.size() >= limit) {
                break;
            }
            AlertStateData data = entry.getValue();
            if (data == null) {
                continue;
            }
            if (Instant.now().isAfter(data.expireAt())) {
                continue;
            }
            if (data.state() != AlertState.PENDING) {
                continue;
            }
            if (data.firstBreachAt() == null) {
                continue;
            }
            result.add(new PendingEntry(
                    entry.getKey().ruleId(),
                    entry.getKey().appid(),
                    data.firstBreachAt(),
                    data.triggerCount()));
        }
        log.trace("[Alerter] scanPending 完成 - size={}", result.size());
        return result;
    }

    public record PendingEntry(Long ruleId, Long appid, Instant firstBreachAt, long triggerCount) {
    }

    private void update(Long ruleId, Long appid, java.util.function.Function<AlertStateData, AlertStateData> mutator) {
        RuleAppKey key = new RuleAppKey(ruleId, appid);
        stateCache.asMap().compute(key, (_, existing) -> {
            AlertStateData current = existing != null ? existing : IDLE_DATA;
            return mutator.apply(current);
        });
    }

    private <T> T readField(Long ruleId, Long appid, java.util.function.Function<AlertStateData, T> extractor) {
        AlertStateData data = stateCache.getIfPresent(new RuleAppKey(ruleId, appid));
        if (data == null || Instant.now().isAfter(data.expireAt())) {
            return null;
        }
        return extractor.apply(data);
    }
}
