package com.springwatch.alerter;

import com.springwatch.analysis.LogAnomalyDetector;
import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.LogEvent;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.AlertHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AlertEngine {

    @Value("${spring-watch.alert.enabled:true}")
    private boolean alertEnabled;

    private final AlertEvaluator evaluator;
    private final AlertStateStore stateStore;
    private final AlertRuleCache ruleCache;
    private final AlertNotifier notifier;
    private final AlertHistoryRepository historyRepository;
    private final LogAnomalyDetector anomalyDetector;

    /**
     * kxj: self代理,解决 @Transactional 内部调用不生效问题
     */
    private final AlertEngine self;

    @Autowired
    public AlertEngine(AlertEvaluator evaluator,
                       AlertStateStore stateStore,
                       AlertRuleCache ruleCache,
                       AlertNotifier notifier,
                       AlertHistoryRepository historyRepository,
                       LogAnomalyDetector anomalyDetector,
                       @Lazy AlertEngine self) {
        this.evaluator = evaluator;
        this.stateStore = stateStore;
        this.ruleCache = ruleCache;
        this.notifier = notifier;
        this.historyRepository = historyRepository;
        this.anomalyDetector = anomalyDetector;
        this.self = self;
    }

    public void process(MetricEvent event) {
        if (!alertEnabled) {
            log.trace("[Alerter] process(MetricEvent) 跳过 - alert.enabled=false, appid={}",
                    event != null ? event.getAppid() : null);
            return;
        }
        if (event == null || event.getAppid() == null) {
            log.debug("[Alerter] process(MetricEvent) 跳过 - event={}", event);
            return;
        }
        List<AlertRule> rules = ruleCache.rulesFor(event.getAppid());
        if (rules.isEmpty()) {
            log.trace("[Alerter] process(MetricEvent) 无匹配规则 - appid={}, metric={}, value={}",
                    event.getAppid(), event.getMetricName(), event.getValue());
            return;
        }
        log.trace("[Alerter] process(MetricEvent) 命中规则 - appid={}, metric={}, value={}, rules={}",
                event.getAppid(), event.getMetricName(), event.getValue(), rules.size());
        rules.stream()
                .filter(rule -> isApplicableMetricRule(rule, event))
                .forEach(rule -> evaluateRuleSafely(rule, event));
    }

    private boolean isApplicableMetricRule(AlertRule rule, MetricEvent event) {
        String type = rule.getRuleType();
        if (!"metric".equals(type) && !"log_error_rate".equals(type)) {
            return false;
        }
        return !"log_error_rate".equals(type) || "log_error_rate".equals(event.getMetricName());
    }

    private void evaluateRuleSafely(AlertRule rule, MetricEvent event) {
        try {
            log.debug("[Alerter] 规则评估开始 - ruleId={}, appid={}, type={}, metric={}, value={}",
                    rule.getId(), event.getAppid(), rule.getRuleType(), event.getMetricName(), event.getValue());
            evaluateRule(rule, event);
        } catch (Exception e) {
            log.warn("[Alerter] 规则评估异常 - ruleId={}, appid={}, error={}",
                    rule.getId(), event.getAppid(), e.getMessage(), e);
        }
    }

    /**
     * kxj: 日志规则评估入口-处理log_keyword/log_new_pattern两类规则
     */
    public void process(LogEvent event) {
        if (!alertEnabled) {
            log.trace("[Alerter] process(LogEvent) 跳过 - alert.enabled=false, appid={}",
                    event != null ? event.getAppid() : null);
            return;
        }
        if (event == null || event.getAppid() == null) {
            log.debug("[Alerter] process(LogEvent) 跳过 - event={}", event);
            return;
        }
        List<AlertRule> rules = ruleCache.rulesFor(event.getAppid());
        if (rules.isEmpty()) {
            log.trace("[Alerter] process(LogEvent) 无匹配规则 - appid={}, fingerprint={}, level={}",
                    event.getAppid(), event.getFingerprint(), event.getLevel());
            return;
        }
        log.trace("[Alerter] process(LogEvent) 命中规则 - appid={}, fingerprint={}, level={}, rules={}",
                event.getAppid(), event.getFingerprint(), event.getLevel(), rules.size());
        rules.stream()
                .filter(rule -> {
                    String type = rule.getRuleType();
                    return "log_keyword".equals(type) || "log_new_pattern".equals(type);
                })
                .forEach(rule -> evaluateLogRuleSafely(rule, event));
    }

    private void evaluateLogRuleSafely(AlertRule rule, LogEvent event) {
        try {
            log.debug("[Alerter] 日志规则评估开始 - ruleId={}, appid={}, type={}, fingerprint={}",
                    rule.getId(), event.getAppid(), rule.getRuleType(), event.getFingerprint());
            evaluateLogRule(rule, event);
        } catch (Exception e) {
            log.warn("[Alerter] 日志规则评估异常 - ruleId={}, appid={}, error={}",
                    rule.getId(), event.getAppid(), e.getMessage(), e);
        }
    }

    private void evaluateRule(AlertRule rule, MetricEvent event) {
        AlertEvaluator.BreachResult result = evaluator.evaluate(rule, event);
        if (result == AlertEvaluator.BreachResult.NOT_APPLICABLE) {
            log.trace("[Alerter] 规则与event不相关, 跳过 - ruleId={}, appid={}, metric={}",
                    rule.getId(), event.getAppid(), event.getMetricName());
            return;
        }
        boolean breached = (result == AlertEvaluator.BreachResult.BREACHED);
        AlertState current = stateStore.getState(rule.getId(), event.getAppid());
        Instant now = Instant.now();
        log.debug("[Alerter] 规则评估结果 - ruleId={}, appid={}, breached={}, currentState={}",
                rule.getId(), event.getAppid(), breached, current);

        if (breached) {
            handleBreach(rule, event, current, now);
        } else {
            handleRecover(rule, event, current, now);
        }
    }

    private void evaluateLogRule(AlertRule rule, LogEvent event) {
        boolean breached;
        if ("log_new_pattern".equals(rule.getRuleType())) {
            breached = anomalyDetector.isNewPattern(event.getAppid(), event.getFingerprint());
        } else {
            breached = evaluator.isLogBreached(rule, event);
        }
        log.debug("[Alerter] 日志规则评估结果 - ruleId={}, appid={}, type={}, breached={}",
                rule.getId(), event.getAppid(), rule.getRuleType(), breached);

        Long ruleId = rule.getId();
        Long appid = event.getAppid();
        Instant now = Instant.now();
        AlertState current = stateStore.getState(ruleId, appid);
        MetricEvent synthetic = toSyntheticMetric(rule, event);

        if (breached) {
            if (current == AlertState.FIRING) {
                log.trace("[Alerter] 日志告警持续中, 跳过 - ruleId={}, appid={}", ruleId, appid);
                return;
            }
            stateStore.setState(ruleId, appid, AlertState.FIRING, now, now);
            log.debug("[Alerter] 日志规则构造合成指标 - ruleId={}, appid={}, metric={}, fingerprint={}",
                    ruleId, appid, synthetic.getMetricName(), event.getFingerprint());
            self.fire(rule, synthetic, now);
            return;
        }

        if (current == AlertState.FIRING) {
            log.info("[Alerter] 日志告警恢复 - ruleId={}, appid={}, fingerprint={}",
                    ruleId, appid, event.getFingerprint());
            stateStore.setState(ruleId, appid, AlertState.RESOLVED, null, null);
            self.resolve(rule, synthetic, now);
            stateStore.clear(ruleId, appid);
        }
    }

    private MetricEvent toSyntheticMetric(AlertRule rule, LogEvent event) {
        Map<String, String> tags = new HashMap<>();
        if (event.getLevel() != null) tags.put("level", event.getLevel());
        if (event.getLogger() != null) tags.put("logger", event.getLogger());
        if (event.getFingerprint() != null) tags.put("fingerprint", event.getFingerprint());
        if (event.getTraceId() != null) tags.put("traceId", event.getTraceId());
        if (event.getMethod() != null) tags.put("method", event.getMethod());
        if (event.getHost() != null) tags.put("host", event.getHost());
        if (event.getMessage() != null) {
            String m = event.getMessage();
            tags.put("message", m.length() > 256 ? m.substring(0, 256) : m);
        }
        MetricEvent built = MetricEvent.builder()
                .appid(event.getAppid())
                .metricName(rule.getRuleType())
                .value(1.0)
                .timestamp(event.getTimestamp() != null ? event.getTimestamp() : Instant.now())
                .tags(tags)
                .build();
        log.debug("[Alerter] 合成指标构建完成 - appid={}, metricName={}, value={}, tagKeys={}",
                built.getAppid(), built.getMetricName(), built.getValue(), tags.keySet());
        return built;
    }

    private void handleBreach(AlertRule rule, MetricEvent event,
                              AlertState current, Instant now) {
        Long ruleId = rule.getId();
        Long appid = event.getAppid();

        log.debug("[Alerter] handleBreach - ruleId={}, appid={}, current={}",
                ruleId, appid, current);

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
            }

            long elapsed = Duration.between(firstBreach, now).toMillis();
            boolean timesMet = times <= 1 || count >= times;
            boolean durationMet = elapsed >= durationSec * 1000L;
            if (timesMet && durationMet) {
                if (stateStore.tryFire(ruleId, appid, firstBreach, now)) {
                    stateStore.clearTriggerCount(ruleId, appid);
                    self.fire(rule, event, now);
                } else {
                    log.debug("[Alerter] CAS抢占FIRING失败, 已被其他线程触发 - ruleId={}, appid={}", ruleId, appid);
                }
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

        log.debug("[Alerter] handleRecover - ruleId={}, appid={}, current={}",
                ruleId, appid, current);

        if (current == AlertState.PENDING) {
            stateStore.clear(ruleId, appid);
            log.debug("[Alerter] PENDING中恢复, 清除状态 - ruleId={}, appid={}", ruleId, appid);
            return;
        }

        if (current == AlertState.FIRING) {
            //TODO 临时补丁
            // kxj: 仅当"触发告警的同一个 metric"再次出现且条件不满足时,才算真正恢复
            // 否则路过的任意 metric(JEXL 评估自然返回 false)会误触发 resolve
            String lastMetric = stateStore.getLastMetric(ruleId, appid);
            if (lastMetric != null && !lastMetric.equals(event.getMetricName())) {
                log.debug("[Alerter] 收到不相关事件, 跳过恢复 - ruleId={}, appid={}, lastMetric={}, currentMetric={}",
                        ruleId, appid, lastMetric, event.getMetricName());
                return;
            }
            if (stateStore.tryResolve(ruleId, appid)) {
                self.resolve(rule, event, now);
                stateStore.clear(ruleId, appid);
            } else {
                log.debug("[Alerter] CAS抢占RESOLVED失败, 已被其他线程恢复 - ruleId={}, appid={}", ruleId, appid);
            }
        }
    }

    @Transactional
    public void fire(AlertRule rule, MetricEvent event, Instant now) {
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
    public void resolve(AlertRule rule, MetricEvent event, Instant now) {
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

    /**
     * kxj: PENDING扫描器触发入口-由PendingStateScanner调用,合成MetricEvent后走fire()
     * 必须在虚拟线程池内调用,避免阻塞扫描器
     */
    public void fireFromScanner(AlertRule rule, Long appid, Instant firstBreachAt, long triggerCount, Instant now) {
        if (!alertEnabled) {
            return;
        }
        if (rule == null || appid == null) {
            return;
        }
        AlertState current = stateStore.getState(rule.getId(), appid);
        if (current != AlertState.PENDING) {
            log.debug("[Alerter] 扫描触发时状态已变更, 跳过 - ruleId={}, appid={}, current={}",
                    rule.getId(), appid, current);
            return;
        }
        int times = rule.getTimes() == null ? 1 : rule.getTimes();
        int durationSec = rule.getDurationSeconds() == null ? 60 : rule.getDurationSeconds();
        long elapsed = Duration.between(firstBreachAt, now).toMillis();
        boolean timesMet = times <= 1 || triggerCount >= times;
        boolean durationMet = elapsed >= durationSec * 1000L;
        if (!timesMet) {
            log.debug("[Alerter] 扫描触发但times未达 - ruleId={}, appid={}, count={}/{}",
                    rule.getId(), appid, triggerCount, times);
            return;
        }
        if (!durationMet) {
            log.debug("[Alerter] 扫描触发但duration未达 - ruleId={}, appid={}, elapsed={}ms, duration={}ms",
                    rule.getId(), appid, elapsed, durationSec * 1000L);
            return;
        }
        String lastMetric = stateStore.getLastMetric(rule.getId(), appid);
        String lastValueStr = stateStore.getLastValue(rule.getId(), appid);
        Double lastValue = null;
        if (lastValueStr != null) {
            try {
                lastValue = Double.parseDouble(lastValueStr);
            } catch (NumberFormatException ignored) {
            }
        }
        Map<String, String> tags = new HashMap<>();
        tags.put("trigger", "scanner");
        MetricEvent synthetic = MetricEvent.builder()
                .appid(appid)
                .metricName(lastMetric != null ? lastMetric : rule.getRuleType())
                .value(lastValue != null ? lastValue : 1.0)
                .timestamp(now)
                .tags(tags)
                .build();
        AlertState recheck = stateStore.getState(rule.getId(), appid);
        if (recheck != AlertState.PENDING) {
            log.debug("[Alerter] 扫描器二次校验时状态已变更, 放弃FIRING - ruleId={}, appid={}, current={}",
                    rule.getId(), appid, recheck);
            return;
        }
        if (stateStore.tryFire(rule.getId(), appid, firstBreachAt, now)) {
            stateStore.clearTriggerCount(rule.getId(), appid);
            log.info("[Alerter] 扫描器触发FIRING - ruleId={}, appid={}, firstBreachAt={}, triggerCount={}, metric={}, value={}",
                    rule.getId(), appid, firstBreachAt, triggerCount, synthetic.getMetricName(), synthetic.getValue());
            self.fire(rule, synthetic, now);
        } else {
            log.debug("[Alerter] 扫描器CAS抢占FIRING失败, 已被实时事件触发 - ruleId={}, appid={}",
                    rule.getId(), appid);
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
        String msg = String.format("[%s][%s] appid=%s 指标 %s 当前值=%.2f 规则=%s 时间=%s",
                determineLevel(event, rule).toUpperCase(),
                type.toUpperCase(), event.getAppid(), event.getMetricName(),
                event.getValue() != null ? event.getValue() : 0.0,
                rule.getExpression(), Instant.now());
        log.debug("[Alerter] 告警消息构建 - ruleId={}, appid={}, message={}", rule.getId(), event.getAppid(), msg);
        return msg;
    }
}
