package com.springwatch.alerter;

import com.springwatch.analysis.LogAnomalyDetector;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.LogEvent;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEngine {

    @Value("${spring-watch.alert.enabled:true}")
    private boolean alertEnabled;

    @Value("${spring-watch.alert.log.recover-grace-seconds:30}")
    private long logRecoverGraceSeconds;

    private final AlertEvaluator evaluator;
    private final AlertStateStore stateStore;
    private final AlertRuleCache ruleCache;
    private final LogAnomalyDetector anomalyDetector;
    private final AlertLifecycleService lifecycleService;

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
                // kxj: 持续命中时续期 lastFiredAt,恢复判断以"最近一次命中时间"为准
                stateStore.updateLastFiredAt(ruleId, appid, now);
                log.trace("[Alerter] 日志告警持续命中,续期 - ruleId={}, appid={}", ruleId, appid);
                return;
            }
            stateStore.setState(ruleId, appid, AlertState.FIRING, now, now);
            log.debug("[Alerter] 日志规则构造合成指标 - ruleId={}, appid={}, metric={}, fingerprint={}",
                    ruleId, appid, synthetic.getMetricName(), event.getFingerprint());
            fireSafely(rule, synthetic);
            return;
        }

        if (current == AlertState.FIRING) {
            // kxj: 仅当"最近一次命中"超过 grace 窗口后才允许恢复,避免普通日志闪断告警
            Instant lastFiredAt = stateStore.getLastFiredAt(ruleId, appid);
            long elapsedSec = lastFiredAt == null
                    ? Long.MAX_VALUE
                    : Duration.between(lastFiredAt, now).toSeconds();
            if (elapsedSec < logRecoverGraceSeconds) {
                log.trace("[Alerter] 日志告警恢复被grace窗口拦截 - ruleId={}, appid={}, elapsed={}s, grace={}s",
                        ruleId, appid, elapsedSec, logRecoverGraceSeconds);
                return;
            }
            log.info("[Alerter] 日志告警恢复 - ruleId={}, appid={}, fingerprint={}, 无命中持续={}s",
                    ruleId, appid, event.getFingerprint(), elapsedSec);
            if (stateStore.tryResolve(ruleId, appid)) {
                resolveSafely(rule, synthetic, now);
                stateStore.clear(ruleId, appid);
            }
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
                    fireSafely(rule, event);
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
            // kxj: lastMetric 为 null(触发事件无指标名/状态已清)时无法判断相关性,
            // 不恢复,交由扫描器超时兜底,避免任意路过事件误关告警
            String lastMetric = stateStore.getLastMetric(ruleId, appid);
            if (lastMetric == null) {
                log.debug("[Alerter] 无lastMetric记录, 跳过恢复(交扫描器兜底) - ruleId={}, appid={}",
                        ruleId, appid);
                return;
            }
            if (!lastMetric.equals(event.getMetricName())) {
                log.debug("[Alerter] 收到不相关事件, 跳过恢复 - ruleId={}, appid={}, lastMetric={}, currentMetric={}",
                        ruleId, appid, lastMetric, event.getMetricName());
                return;
            }
            if (stateStore.tryResolve(ruleId, appid)) {
                resolveSafely(rule, event, now);
                stateStore.clear(ruleId, appid);
            } else {
                log.debug("[Alerter] CAS抢占RESOLVED失败, 已被其他线程恢复 - ruleId={}, appid={}", ruleId, appid);
            }
        }
    }

    /**
     * kxj: P0-1 修复 - fire 失败(DB/通知异常)时把状态回退到 PENDING,由后续事件或扫描器重试,不丢告警
     */
    private void fireSafely(AlertRule rule, MetricEvent event) {
        try {
            lifecycleService.fire(rule, event);
        } catch (Exception e) {
            log.error("[Alerter] 告警触发失败,回退状态到PENDING待重试 - ruleId={}, appid={}, error={}",
                    rule.getId(), event.getAppid(), e.getMessage(), e);
            stateStore.setState(rule.getId(), event.getAppid(), AlertState.PENDING, null, null);
        }
    }

    /**
     * kxj: resolve 失败(DB/通知异常)时回退到 FIRING,由后续事件或扫描器重试
     */
    private void resolveSafely(AlertRule rule, MetricEvent event, Instant now) {
        try {
            lifecycleService.resolve(rule, event, now);
        } catch (Exception e) {
            log.error("[Alerter] 告警恢复失败,回退状态到FIRING待重试 - ruleId={}, appid={}, error={}",
                    rule.getId(), event.getAppid(), e.getMessage(), e);
            stateStore.setState(rule.getId(), event.getAppid(), AlertState.FIRING, null, now);
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
            fireSafely(rule, synthetic);
        } else {
            log.debug("[Alerter] 扫描器CAS抢占FIRING失败, 已被实时事件触发 - ruleId={}, appid={}",
                    rule.getId(), appid);
        }
    }

    /**
     * kxj: P0-4 修复 - 扫描器兜底入口,关闭"数据停止上报"导致的 stale FIRING 遗留告警
     */
    public void resolveFromScanner(AlertRule rule, Long appid, Instant now) {
        if (!alertEnabled) {
            return;
        }
        if (rule == null || appid == null) {
            return;
        }
        AlertState current = stateStore.getState(rule.getId(), appid);
        if (current != AlertState.FIRING) {
            log.debug("[Alerter] 扫描器兜底恢复时状态已变更, 跳过 - ruleId={}, appid={}, current={}",
                    rule.getId(), appid, current);
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
        tags.put("trigger", "scanner-stale-recover");
        MetricEvent synthetic = MetricEvent.builder()
                .appid(appid)
                .metricName(lastMetric != null ? lastMetric : rule.getRuleType())
                .value(lastValue != null ? lastValue : 1.0)
                .timestamp(now)
                .tags(tags)
                .build();
        if (stateStore.tryResolve(rule.getId(), appid)) {
            log.info("[Alerter] 扫描器兜底恢复FIRING - ruleId={}, appid={}, metric={}, value={}",
                    rule.getId(), appid, synthetic.getMetricName(), synthetic.getValue());
            resolveSafely(rule, synthetic, now);
            stateStore.clear(rule.getId(), appid);
        } else {
            log.debug("[Alerter] 扫描器兜底CAS抢占RESOLVED失败, 已被其他线程恢复 - ruleId={}, appid={}",
                    rule.getId(), appid);
        }
    }
}
