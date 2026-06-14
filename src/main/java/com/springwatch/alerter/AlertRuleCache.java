package com.springwatch.alerter;

import com.springwatch.model.entity.AlertRule;
import com.springwatch.repository.AlertRuleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertRuleCache {

    private final AlertRuleRepository repository;

    @Value("${spring-watch.alert.rule-cache.refresh-interval-ms:30000}")
    private long refreshIntervalMs;

    private final AtomicReference<Map<Long, List<AlertRule>>> cache = new AtomicReference<>(Collections.emptyMap());

    @PostConstruct
    void init() {
        refresh();
        log.info("[Alerter] 规则缓存初始化完成 - refreshInterval={}ms", refreshIntervalMs);
    }

    @Scheduled(fixedDelayString = "${spring-watch.alert.rule-cache.refresh-interval-ms:30000}")
    public void scheduledRefresh() {
        refresh();
    }

    public void refresh() {
        try {
            List<AlertRule> all = repository.findByStatus("enabled");
            Map<Long, List<AlertRule>> grouped = all.stream()
                    .filter(r -> r.getApp() != null && r.getApp().getAppid() != null)
                    .collect(Collectors.groupingBy(r -> r.getApp().getAppid()));
            cache.set(grouped);
            log.debug("[Alerter] 规则缓存刷新 - rules={}, apps={}", all.size(), grouped.size());
        } catch (Exception e) {
            log.warn("[Alerter] 规则缓存刷新失败, 继续用旧缓存 - error={}", e.getMessage());
        }
    }

    public List<AlertRule> rulesFor(Long appid) {
        return cache.get().getOrDefault(appid, Collections.emptyList());
    }

    public int size() {
        return cache.get().values().stream().mapToInt(List::size).sum();
    }
}
