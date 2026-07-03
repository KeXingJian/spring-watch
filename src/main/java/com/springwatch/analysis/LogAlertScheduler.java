package com.springwatch.analysis;

import com.springwatch.alerter.AsyncAlertExecutor;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.AlertRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogAlertScheduler {

    private final LogAggregator aggregator;
    private final AlertRuleRepository ruleRepository;
    private final AsyncAlertExecutor alertExecutor;

    @Value("${spring-watch.log.alert.error-rate-window-seconds:60}")
    private long windowSeconds;

    /**
     * kxj: log_error_rate定时任务-每分钟查询窗口错误率,合成MetricEvent触发AlertEngine状态机
     * 用 fixedRate(非 fixedDelay)避免执行慢时窗口出现间隙
     */
    @Scheduled(fixedRateString = "${spring-watch.log.alert.error-rate-interval-ms:60000}")
    public void evaluateErrorRateRules() {
        log.debug("[kxj: LogAlertScheduler 调度开始 - window={}s", windowSeconds);
        List<AlertRule> rules;
        try {
            rules = ruleRepository.findByRuleTypeAndStatus("log_error_rate", "enabled");
        } catch (Exception e) {
            log.warn("[kxj: LogAlertScheduler 加载规则失败 - error={}]", e.getMessage());
            return;
        }
        if (rules.isEmpty()) {
            log.debug("[kxj: LogAlertScheduler 无log_error_rate规则, 跳过");
            return;
        }
        log.debug("[kxj: LogAlertScheduler 加载规则 - rules={}", rules.size());
        Instant now = Instant.now();
        Instant from = now.minusSeconds(windowSeconds);
        long submitted = rules.stream()
                .filter(rule -> rule.getApp() != null && rule.getApp().getAppid() != null)
                .mapToLong(rule -> {
                    try {
                        submitErrorRate(rule, from, now);
                        return 1L;
                    } catch (Exception e) {
                        log.warn("[kxj: LogAlertScheduler 规则评估异常 - ruleId={}, error={}]",
                                rule.getId(), e.getMessage());
                        return 0L;
                    }
                })
                .sum();
        if (submitted > 0) {
            log.info("[kxj: LogAlertScheduler 评估完成 - rules={}, submitted={}, window={}s]",
                    rules.size(), submitted, windowSeconds);
        }
    }

    private void submitErrorRate(AlertRule rule, Instant from, Instant now) {
        long appid = rule.getApp().getAppid();
        LogAggregator.ErrorRateStats stats = aggregator.errorRate(appid, from, now);
        double percent = stats.errorRate() * 100.0;
        MetricEvent synthetic = MetricEvent.builder()
                .appid(appid)
                .metricName("log_error_rate")
                .value(percent)
                .timestamp(now)
                .build();
        alertExecutor.submit(synthetic);
        log.debug("[kxj: LogAlertScheduler 规则评估提交 - ruleId={}, appid={}, percent={}, threshold={}]",
                rule.getId(), appid, percent, rule.getThresholdValue());
    }
}
