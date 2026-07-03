package com.springwatch.ingest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.springwatch.repository.LogDedupCountRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogDedupService {

    public record DedupKey(long appid, String fingerprint) {
    }

    private final LogDedupCountRepository dedupCountRepository;
    private final MeterRegistry meterRegistry;

    @Value("${spring-watch.log.dedup.window-seconds:60}")
    private long windowSeconds;

    @Value("${spring-watch.log.dedup.max-entries:200000}")
    private long maxEntries;


    private Cache<DedupKey, LongAdder> dedupCache;
    private Counter keepCounter;
    private Counter dropCounter;
    private Counter flushCounter;
    private Counter flushFailCounter;
    private Counter sizeEvictionCounter;
    private Counter expiredEvictionCounter;

    @PostConstruct
    void init() {
        this.dedupCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(windowSeconds))
                .maximumSize(maxEntries)
                .recordStats()
                .removalListener((_, _, cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        sizeEvictionCounter.increment();
                    } else if (cause == RemovalCause.EXPIRED) {
                        expiredEvictionCounter.increment();
                    }
                })
                .build();

        this.keepCounter = Counter.builder("spring.watch.ingest.log.dedup.keep")
                .description("日志 dedup 保留条数")
                .register(meterRegistry);
        this.dropCounter = Counter.builder("spring.watch.ingest.log.dedup.drop")
                .description("日志 dedup 丢弃条数")
                .register(meterRegistry);
        this.flushCounter = Counter.builder("spring.watch.ingest.log.dedup.flush")
                .description("日志 dedup 计数双写条数")
                .register(meterRegistry);
        this.flushFailCounter = Counter.builder("spring.watch.ingest.log.dedup.flush_fail")
                .description("日志 dedup 计数双写失败条数")
                .register(meterRegistry);
        this.sizeEvictionCounter = Counter.builder("spring.watch.ingest.log.dedup.evict_size")
                .description("日志 dedup 因容量上限被淘汰的次数(趋近 0,超 0 说明流量超配需扩 max-entries)")
                .register(meterRegistry);
        this.expiredEvictionCounter = Counter.builder("spring.watch.ingest.log.dedup.evict_expired")
                .description("日志 dedup 因窗口 TTL 到期被淘汰的次数(正常)")
                .register(meterRegistry);

        Gauge.builder("spring.watch.ingest.log.dedup.cache_size", dedupCache, c -> (double) c.estimatedSize())
                .description("日志 dedup 当前缓存 entry 数")
                .register(meterRegistry);
        Gauge.builder("spring.watch.ingest.log.dedup.cache_max", this, s -> (double) s.maxEntries)
                .description("日志 dedup 硬上限 entry 数")
                .register(meterRegistry);
    }

    public boolean shouldKeep(long appid, String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return true;
        }
        DedupKey key = new DedupKey(appid, fingerprint);
        LongAdder existing = dedupCache.asMap().putIfAbsent(key, new LongAdder());
        if (existing == null) {
            keepCounter.increment();
            return true;
        }
        existing.increment();
        dropCounter.increment();
        return false;
    }

    @Scheduled(fixedDelayString = "${spring-watch.log.dedup.flush-interval-ms:30000}")
    @Transactional
    public void flushDirtyCounts() {
        int flushed = 0;
        int failed = 0;
        Instant now = Instant.now();
        for (Map.Entry<DedupKey, LongAdder> entry : dedupCache.asMap().entrySet()) {
            long count = entry.getValue().sumThenReset();
            if (count <= 0) {
                continue;
            }
            try {
                dedupCountRepository.upsertAddCount(
                        entry.getKey().appid(),
                        entry.getKey().fingerprint(),
                        count,
                        now);
                flushed++;
            } catch (Exception e) {
                failed++;
                log.warn("[kxj: dedup flush 失败 - appid={}, fingerprint={}, error={}]",
                        entry.getKey().appid(), entry.getKey().fingerprint(), e.getMessage());
            }
        }
        dedupCache.cleanUp();
        flushCounter.increment(flushed);
        if (failed > 0) {
            flushFailCounter.increment(failed);
        }
        if (flushed > 0 || failed > 0) {
            log.info("[kxj: dedup flush 完成 - flushed={}, failed={}]",
                    flushed, failed);
        }
    }
}
