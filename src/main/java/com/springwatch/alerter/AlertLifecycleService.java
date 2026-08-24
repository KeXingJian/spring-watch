package com.springwatch.alerter;

import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * kxj: 告警触发/恢复的落库+通知事务 Bean。
 * 从 AlertEngine 拆出,解决 @Transactional 内部调用失效问题(替代原 @Lazy self 代理),
 * 使 AlertEngine 可安全使用 @RequiredArgsConstructor 注入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertLifecycleService {

    private final AlertStateStore stateStore;
    private final AlertNotifier notifier;
    private final AlertHistoryRepository historyRepository;

    @Transactional
    public void fire(AlertRule rule, MetricEvent event) {
        log.info("[Alerter] 告警触发 - ruleId={}, appid={}, metric={}, value={}, expression={}",
                rule.getId(), event.getAppid(), event.getMetricName(),
                event.getValue(), rule.getExpression());
        if (event.getMetricName() != null) {
            stateStore.recordLastEvent(rule.getId(), event.getAppid(),
                    event.getValue(), event.getMetricName(), null);
        }

        AlertHistory history = AlertHistory.builder()
                .rule(rule)
                .app(rule.getApp())
                .alertLevel(determineLevel(rule))
                .alertMessage(buildMessage(rule, event))
                .build();
        AlertHistory saved = historyRepository.save(history);

        String notifyResult = notifier.notify(rule, event, "firing");
        saved.setNotifyResult(notifyResult);
        historyRepository.save(saved);
        log.info("[Alerter] 告警历史持久化 - historyId={}, notifyResult={}", saved.getId(), notifyResult);
    }

    @Transactional
    public void resolve(AlertRule rule, MetricEvent event, Instant now) {
        log.info("[Alerter] 告警恢复 - ruleId={}, appid={}, metric={}",
                rule.getId(), event.getAppid(), event.getMetricName());

        List<AlertHistory> open = historyRepository
                .findByAppAppidAndRuleIdAndResolvedAtIsNullOrderByCreatedAtDesc(
                        event.getAppid(), rule.getId());
        if (!open.isEmpty()) {
            AlertHistory latest = open.getFirst();
            latest.setResolvedAt(now);
            historyRepository.save(latest);
            log.info("[Alerter] 告警历史标记恢复 - historyId={}, resolvedAt={}", latest.getId(), now);
        } else {
            log.warn("[Alerter] 恢复时未找到open历史 - ruleId={}, appid={}", rule.getId(), event.getAppid());
        }

        try {
            notifier.notify(rule, event, "resolved");
        } catch (Exception e) {
            log.warn("[Alerter] 恢复通知失败 - ruleId={}, error={}", rule.getId(), e.getMessage());
        }
    }

    private String determineLevel(AlertRule rule) {
        String level = rule.getLevel();
        if (level == null || level.isBlank()) {
            return "warning";
        }
        return level;
    }

    private String buildMessage(AlertRule rule, MetricEvent event) {
        return String.format("[%s][%s] appid=%s 指标 %s 当前值=%.2f 规则=%s 时间=%s",
                determineLevel(rule).toUpperCase(),
                "firing".toUpperCase(), event.getAppid(), event.getMetricName(),
                event.getValue() != null ? event.getValue() : 0.0,
                rule.getExpression(), Instant.now());
    }
}
