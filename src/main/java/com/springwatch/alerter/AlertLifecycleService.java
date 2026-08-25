package com.springwatch.alerter;

import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.AlertTriggeredEvent;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    private final AlertAggregationService aggregationService;

    @Transactional
    public void fire(AlertRule rule, MetricEvent event) {
        log.info("[Alerter] 告警触发 - ruleId={}, appid={}, metric={}, value={}, expression={}",
                rule.getId(), event.getAppid(), event.getMetricName(),
                event.getValue(), rule.getExpression());
        if (event.getMetricName() != null) {
            stateStore.recordLastEvent(rule.getId(), event.getAppid(),
                    event.getValue(), event.getMetricName(), null);
        }

        // P1 告警收敛:同一相似指纹在静默窗口内聚为收敛组,仅首报通知
        AlertAggregationService.Decision agg = aggregationService.decide(rule, event);

        AlertHistory history = AlertHistory.builder()
                .rule(rule)
                .app(rule.getApp())
                .alertLevel(determineLevel(rule))
                .alertMessage(buildMessage(rule, event))
                .aggGroupId(agg.groupId())
                .aggRole(agg.leader() ? "leader" : "member")
                .aggSuppressed(!agg.leader())
                .aggGroupCount(agg.groupCount())
                .aggSuppressedCount(agg.groupCount() - 1)
                .build();
        AlertHistory saved = historyRepository.save(history);

        if (agg.leader()) {
            String notifyResult = notifier.notify(rule, event, "firing");
            saved.setNotifyResult(notifyResult);
            historyRepository.save(saved);
            log.info("[Alerter] 告警首报通知 - historyId={}, aggGroupId={}, notifyResult={}",
                    saved.getId(), agg.groupId(), notifyResult);
            eventPublisher.publishEvent(new AlertTriggeredEvent(
                    rule, event.getAppid(), event.getMetricName(), event.getValue(),
                    saved.getId(), Instant.now()));
        } else {
            saved.setNotifyResult("{\"status\":\"suppressed\",\"reason\":\"converged_similar\",\"group\":\""
                    + agg.groupId() + "\",\"count\":" + agg.groupCount() + "}");
            historyRepository.save(saved);
            log.info("[Alerter] 告警被收敛抑制(同类静默) - historyId={}, aggGroupId={}, count={}",
                    saved.getId(), agg.groupId(), agg.groupCount());
        }
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
