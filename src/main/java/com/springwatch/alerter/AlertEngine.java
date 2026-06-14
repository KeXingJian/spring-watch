package com.springwatch.alerter;

import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEngine {

    private final AlertEvaluator evaluator;
    private final AlertStateStore stateStore;
    private final AlertRuleCache ruleCache;
    private final AlertNotifier notifier;
    private final AlertHistoryRepository historyRepository;

    public void process(MetricEvent event) {
        if (event == null || event.getAppid() == null) {
            return;
        }
        List<AlertRule> rules = ruleCache.rulesFor(event.getAppid());
        if (rules.isEmpty()) {
            return;
        }
        for (AlertRule rule : rules) {
            try {
                evaluateRule(rule, event);
            } catch (Exception e) {
                log.warn("[Alerter] 规则评估异常 - ruleId={}, appid={}, error={}",
                        rule.getId(), event.getAppid(), e.getMessage(), e);
            }
        }
    }

    private void evaluateRule(AlertRule rule, MetricEvent event) {
        boolean breached = evaluator.isBreached(rule, event);
        AlertState current = stateStore.getState(rule.getId(), event.getAppid());
        Instant now = Instant.now();

        if (breached) {
            handleBreach(rule, event, current, now);
        } else {
            handleRecover(rule, event, current, now);
        }
    }

    private void handleBreach(AlertRule rule, MetricEvent event,
                              AlertState current, Instant now) {
        Long ruleId = rule.getId();
        Long appid = event.getAppid();

        if (current == AlertState.FIRING) {
            log.trace("[Alerter] 告警持续中, 跳过 - ruleId={}, appid={}", ruleId, appid);
            return;
        }

        if (current == AlertState.IDLE || current == AlertState.RESOLVED) {
            stateStore.setState(ruleId, appid, AlertState.PENDING, now, null);
            stateStore.clearTriggerCount(ruleId, appid);
            log.debug("[Alerter] 条件首次满足, 进入PENDING - ruleId={}, appid={}, metric={}, value={}",
                    ruleId, appid, event.getMetricName(), event.getValue());
            return;
        }

        if (current == AlertState.PENDING) {
            Instant firstBreach = stateStore.getFirstBreachAt(ruleId, appid);
            int times = rule.getTimes() == null ? 1 : rule.getTimes();
            int durationSec = rule.getDurationSeconds() == null ? 60 : rule.getDurationSeconds();
            if (firstBreach == null) {
                stateStore.setState(ruleId, appid, AlertState.PENDING, now, null);
                return;
            }

            long count = 0L;
            if (times > 1) {
                count = stateStore.incrementTriggerCount(ruleId, appid);
                if (count >= times) {
                    stateStore.setState(ruleId, appid, AlertState.FIRING, firstBreach, now);
                    stateStore.clearTriggerCount(ruleId, appid);
                    fire(rule, event, now);
                    return;
                }
            }

            long elapsed = Duration.between(firstBreach, now).toMillis();
            if (elapsed >= durationSec * 1000L) {
                stateStore.setState(ruleId, appid, AlertState.FIRING, firstBreach, now);
                stateStore.clearTriggerCount(ruleId, appid);
                fire(rule, event, now);
            } else {
                log.trace("[Alerter] 条件持续中, 未达触发条件 - ruleId={}, appid={}, count={}/{}, elapsed={}ms, duration={}ms",
                        ruleId, appid, count, times, elapsed, durationSec * 1000L);
            }
        }
    }

    private void handleRecover(AlertRule rule, MetricEvent event,
                                AlertState current, Instant now) {
        Long ruleId = rule.getId();
        Long appid = event.getAppid();

        if (current == AlertState.PENDING) {
            stateStore.clear(ruleId, appid);
            log.debug("[Alerter] PENDING中恢复, 清除状态 - ruleId={}, appid={}", ruleId, appid);
            return;
        }

        if (current == AlertState.FIRING) {
            stateStore.setState(ruleId, appid, AlertState.RESOLVED, null, null);
            resolve(rule, event, now);
            stateStore.clear(ruleId, appid);
        }
    }

    @Transactional
    protected void fire(AlertRule rule, MetricEvent event, Instant now) {
        log.info("[Alerter] 告警触发 - ruleId={}, appid={}, metric={}, value={}, expression={}",
                rule.getId(), event.getAppid(), event.getMetricName(),
                event.getValue(), rule.getExpression());

        AlertHistory history = AlertHistory.builder()
                .rule(rule)
                .app(rule.getApp())
                .alertLevel(determineLevel(event, rule))
                .alertMessage(buildMessage(rule, event, "firing"))
                .build();
        AlertHistory saved = historyRepository.save(history);

        String notifyResult = notifier.notify(rule, event, "firing");
        saved.setNotifyResult(notifyResult);
        historyRepository.save(saved);
        log.info("[Alerter] 告警历史持久化 - historyId={}, notifyResult={}", saved.getId(), notifyResult);
    }

    @Transactional
    protected void resolve(AlertRule rule, MetricEvent event, Instant now) {
        log.info("[Alerter] 告警恢复 - ruleId={}, appid={}, metric={}",
                rule.getId(), event.getAppid(), event.getMetricName());

        List<AlertHistory> open = historyRepository
                .findByAppAppidAndRuleIdAndResolvedAtIsNullOrderByCreatedAtDesc(
                        event.getAppid(), rule.getId());
        if (!open.isEmpty()) {
            AlertHistory latest = open.get(0);
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

    private String determineLevel(MetricEvent event, AlertRule rule) {
        String level = rule.getLevel();
        if (level == null || level.isBlank()) {
            return "warning";
        }
        return level;
    }

    private String buildMessage(AlertRule rule, MetricEvent event, String type) {
        return String.format("[%s][%s] appid=%s 指标 %s 当前值=%.2f 规则=%s 时间=%s",
                determineLevel(event, rule).toUpperCase(),
                type.toUpperCase(), event.getAppid(), event.getMetricName(),
                event.getValue() != null ? event.getValue() : 0.0,
                rule.getExpression(), Instant.now());
    }
}
