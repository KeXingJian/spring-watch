package com.springwatch.analysis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashSet;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogAnomalyDetector {

    private final MeterRegistry meterRegistry;

    @Value("${spring-watch.log.anomaly.rate-ttl-seconds:600}")
    private long rateTtlSeconds;

    @Value("${spring-watch.log.anomaly.pattern-ttl-hours:168}")
    private long patternTtlHours;

    @Value("${spring-watch.log.anomaly.min-base-rate:0.01}")
    private double minBaseRate;

    @Value("${spring-watch.log.anomaly.max-appids:200}")
    private long maxAppids;

    @Value("${spring-watch.log.anomaly.max-patterns-per-appid:5000}")
    private int maxPatternsPerAppid;

    private Cache<Long, Double> errorRateCache;
    private Cache<Long, PatternSet> knownPatternsCache;
    private Counter rateEvictSizeCounter;
    private Counter patternEvictSizeCounter;
    private Counter newPatternCounter;

    @PostConstruct
    void init() {
        this.errorRateCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(rateTtlSeconds))
                .maximumSize(maxAppids)
                .recordStats()
                .removalListener((_, _, cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        rateEvictSizeCounter.increment();
                    }
                })
                .build();

        this.knownPatternsCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(patternTtlHours))
                .maximumSize(maxAppids)
                .recordStats()
                .removalListener((_, _, cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        patternEvictSizeCounter.increment();
                    }
                })
                .build();

        this.rateEvictSizeCounter = Counter.builder("spring.watch.ingest.log.anomaly.rate_evict_size")
                .description("异常检测 lastRate 因容量上限被 LRU 淘汰次数")
                .register(meterRegistry);
        this.patternEvictSizeCounter = Counter.builder("spring.watch.ingest.log.anomaly.pattern_evict_size")
                .description("异常检测 knownPatterns 因容量上限被 LRU 淘汰次数")
                .register(meterRegistry);
        this.newPatternCounter = Counter.builder("spring.watch.ingest.log.anomaly.new_pattern")
                .description("新模式命中次数")
                .register(meterRegistry);

        Gauge.builder("spring.watch.ingest.log.anomaly.rate_cache_size", errorRateCache, c -> (double) c.estimatedSize())
                .description("异常检测 lastRate 缓存当前 entry 数")
                .register(meterRegistry);
        Gauge.builder("spring.watch.ingest.log.anomaly.patterns_cache_size", knownPatternsCache, c -> (double) c.estimatedSize())
                .description("异常检测 knownPatterns 缓存当前 entry 数")
                .register(meterRegistry);
    }

    public SpikingResult isErrorRateSpiking(long appid, double currentRate, double multiplier) {
        log.debug("[kxj: LogAnomalyDetector isErrorRateSpiking - appid={}, current={}, multiplier={}]", appid, currentRate, multiplier);
        Double lastRate = errorRateCache.get(appid, _ -> null);
        errorRateCache.put(appid, currentRate);
        if (lastRate == null || lastRate < minBaseRate) {
            log.debug("[kxj: LogAnomalyDetector 跳过突增判断 - appid={}, lastRate={}, minBaseRate={}", appid, lastRate, minBaseRate);
            return new SpikingResult(false, lastRate);
        }
        boolean spiking = currentRate / lastRate >= multiplier;
        if (spiking) {
            log.info("[kxj: LogAnomalyDetector 错误率突增 - appid={}, current={}, last={}, multiplier={}]",
                    appid, currentRate, lastRate, multiplier);
        } else {
            log.debug("[kxj: LogAnomalyDetector 错误率未突增 - appid={}, current={}, last={}, ratio={}",
                    appid, currentRate, lastRate, currentRate / lastRate);
        }
        return new SpikingResult(spiking, lastRate);
    }

    public record SpikingResult(boolean spiking, Double lastRate) {
    }

    public boolean isNewPattern(long appid, String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            log.debug("[kxj: LogAnomalyDetector isNewPattern fingerprint为空, 跳过 - appid={}", appid);
            return false;
        }
        PatternSet set = knownPatternsCache.get(appid, _ -> new PatternSet(maxPatternsPerAppid));
        boolean isNew = set.add(fingerprint);
        if (isNew) {
            newPatternCounter.increment();
            log.info("[kxj: LogAnomalyDetector 新模式 - appid={}, fingerprint={}]", appid, fingerprint);
        } else {
            log.debug("[kxj: LogAnomalyDetector 模式已存在 - appid={}, fingerprint={}", appid, fingerprint);
        }
        return isNew;
    }


    static final class PatternSet {
        private final int maxSize;
        private final LinkedHashSet<String> set = new LinkedHashSet<>();

        PatternSet(int maxSize) {
            this.maxSize = maxSize;
        }

        synchronized boolean add(String s) {
            if (set.contains(s)) {
                return false;
            }
            if (set.size() >= maxSize) {
                Iterator<String> it = set.iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            return set.add(s);
        }
    }
}
