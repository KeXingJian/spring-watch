package com.springwatch.alerter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.repository.AlertRuleRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警规则缓存。
 * P2-1: 改用 Caffeine 替代 AtomicReference&lt;Map&gt;，提供 LRU 淘汰与命中率统计。
 * 缓存 key = appid，value = 该 appid 下的 enabled 规则列表。
 */
@Slf4j
@Component
public class AlertRuleCache {

    private final AlertRuleRepository repository;

    @Value("${spring-watch.alert.rule-cache.refresh-interval-ms:30000}")
    private long refreshIntervalMs;

    @Value("${spring-watch.alert.rule-cache.max-appids:10000}")
    private long maxAppids;

    private volatile Cache<Long, List<AlertRule>> cache;

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);

    public AlertRuleCache(AlertRuleRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void init() {
        rebuild();
        log.info("[Alerter] 规则缓存初始化完成 - refreshInterval={}ms, maxAppids={}",
                refreshIntervalMs, maxAppids);
    }

    @Scheduled(fixedDelayString = "${spring-watch.alert.rule-cache.refresh-interval-ms:30000}")
    public void scheduledRefresh() {
        rebuild();
    }

    private void rebuild() {
        try {
            // 全量 enabled 规则按 appid 预分组
            List<AlertRule> all = repository.findByStatus("enabled");
            long total = all.size();
            Map<Long, List<AlertRule>> grouped = all.stream()
                    .filter(r -> r.getApp() != null && r.getApp().getAppid() != null)
                    .collect(java.util.stream.Collectors.groupingBy(r -> r.getApp().getAppid()));
            if (total != grouped.values().stream().mapToInt(List::size).sum()) {
                log.warn("[Alerter] 规则缓存过滤了 app/appid 为空的规则 - total={}, kept={}",
                        total, grouped.values().stream().mapToInt(List::size).sum());
            }
            Cache<Long, List<AlertRule>> newCache = Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofMillis(refreshIntervalMs * 2))
                    .maximumSize(maxAppids)
                    .removalListener((key, value, cause) -> {
                        if (cause.wasEvicted()) evictions.incrementAndGet();
                    })
                    .build();
            grouped.forEach(newCache::put);
            this.cache = newCache;
            log.debug("[Alerter] 规则缓存刷新 - rules={}, apps={}, hits={}, misses={}, evictions={}",
                    total, grouped.size(), hits.get(), misses.get(), evictions.get());
        } catch (Exception e) {
            log.warn("[Alerter] 规则缓存刷新失败, 继续用旧缓存 - error={}", e.getMessage());
        }
    }

    /** 兼容旧接口（AlertRuleService.create/update/delete 后调用） */
    public void refresh() {
        scheduledRefresh();
    }

    public List<AlertRule> rulesFor(Long appid) {
        Cache<Long, List<AlertRule>> c = cache;
        if (c == null) return Collections.emptyList();
        List<AlertRule> rules = c.get(appid, k -> {
            misses.incrementAndGet();
            return Collections.emptyList();
        });
        if (rules != null && !rules.isEmpty()) {
            hits.incrementAndGet();
        }
        log.trace("[Alerter] 规则查询 - appid={}, hit={}", appid, rules == null ? 0 : rules.size());
        return rules == null ? Collections.emptyList() : rules;
    }

    public int size() {
        Cache<Long, List<AlertRule>> c = cache;
        if (c == null) return 0;
        return (int) c.estimatedSize();
    }

    public CacheStats stats() {
        return new CacheStats(hits.get(), misses.get(), evictions.get(), size());
    }

    public record CacheStats(long hits, long misses, long evictions, int size) {
        public double hitRatio() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }
}
