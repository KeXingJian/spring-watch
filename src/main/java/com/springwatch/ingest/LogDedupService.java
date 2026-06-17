package com.springwatch.ingest;

import com.springwatch.repository.LogDedupCountRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogDedupService {

    private static final String KEY_PREFIX = "log:dedup:";
    private static final String COUNT_PREFIX = "log:dedup:count:";
    private static final String DIRTY_SET = "log:dedup:dirty";

    private final StringRedisTemplate redis;
    private final LogDedupCountRepository dedupCountRepository;
    private final MeterRegistry meterRegistry;

    @Value("${spring-watch.log.dedup.window-seconds:60}")
    private long windowSeconds;

    @Value("${spring-watch.log.dedup.count-ttl-seconds:3600}")
    private long countTtlSeconds;


    private Counter keepCounter;
    private Counter dropCounter;
    private Counter flushCounter;
    private Counter flushFailCounter;

    @jakarta.annotation.PostConstruct
    void initMetrics() {
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
    }

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
            keepCounter.increment();
            return true;
        }
        String countKey = COUNT_PREFIX + appid + ":" + fingerprint;
        redis.opsForValue().increment(countKey);
        redis.expire(countKey, Duration.ofSeconds(countTtlSeconds));
        redis.opsForSet().add(DIRTY_SET, appid + ":" + fingerprint);
        dropCounter.increment();
        return false;
    }


    /**
     * kxj: P0 dedup 双写 - 定期把 Redis 中的累加计数刷到 PostgreSQL,
     * 防止 Redis 重启/挂掉导致历史 dedup 计数全丢
     * 整批 dirty 共享一个事务,@Modifying native query 必须有事务上下文
     */
    @Scheduled(fixedDelayString = "${spring-watch.log.dedup.flush-interval-ms:30000}")
    @Transactional
    public void flushDirtyCounts() {
        Set<String> dirty;
        try {
            dirty = redis.opsForSet().members(DIRTY_SET);
        } catch (Exception e) {
            log.warn("[spring-watch: dedup flush 取dirty集合失败 - error={}]", e.getMessage());
            return;
        }
        if (dirty == null || dirty.isEmpty()) {
            return;
        }
        int flushed = 0;
        int failed = 0;
        Set<String> processed = new HashSet<>();
        for (String key : dirty) {
            String[] parts = key.split(":", 2);
            if (parts.length != 2) {
                redis.opsForSet().remove(DIRTY_SET, key);
                continue;
            }
            long appid;
            try {
                appid = Long.parseLong(parts[0]);
            } catch (NumberFormatException nfe) {
                redis.opsForSet().remove(DIRTY_SET, key);
                continue;
            }
            String fp = parts[1];
            String countStr = redis.opsForValue().get(COUNT_PREFIX + key);
            if (countStr == null) {
                redis.opsForSet().remove(DIRTY_SET, key);
                continue;
            }
            long count;
            try {
                count = Long.parseLong(countStr);
            } catch (NumberFormatException nfe) {
                redis.opsForSet().remove(DIRTY_SET, key);
                continue;
            }
            try {
                dedupCountRepository.upsertAddCount(appid, fp, count, Instant.now());
                redis.opsForSet().remove(DIRTY_SET, key);
                flushed++;
                processed.add(key);
            } catch (Exception e) {
                failed++;
                log.warn("[spring-watch: dedup flush 失败 - appid={}, fingerprint={}, error={}]",
                        appid, fp, e.getMessage());
            }
        }
        flushCounter.increment(flushed);
        if (failed > 0) {
            flushFailCounter.increment(failed);
        }
        if (flushed > 0 || failed > 0) {
            log.info("[spring-watch: dedup flush 完成 - flushed={}, failed={}, dirty={}]",
                    flushed, failed, dirty.size());
        }
    }

    /**
     * kxj: 启动时全量扫描一次历史 count(适合 Redis 重启后从 DB 恢复 COUNT 上下文)
     * 一般情况下 dirty set 不会有内容,但若 Redis 刚启动,这里把 DB 已有的指纹重新 SADD 到 dirty
     * 等待下次 flush 周期正常调度即可,不需要主动拉取 Redis(空 Redis 拉不到)
     */
    @Scheduled(initialDelay = 5000, fixedDelay = 300_000L)
    public void healthCheck() {
        try {
            Long dirtySize = redis.opsForSet().size(DIRTY_SET);
            if (dirtySize == null) {
                return;
            }
            log.debug("[spring-watch: dedup flush dirty 集合大小 - size={}]", dirtySize);
        } catch (Exception e) {
            log.debug("[spring-watch: dedup flush health check 失败 - error={}]", e.getMessage());
        }
    }
}
