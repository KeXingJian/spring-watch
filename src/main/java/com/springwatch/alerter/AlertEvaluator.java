package com.springwatch.alerter;

import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.AlertRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEvaluator {

    private final AlertRuleRepository alertRuleRepository;
    private final AlertWindowManager alertWindowManager;
    private final AlertNotifier alertNotifier;

    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("(\\w[\\w.]*)\\s*(>|<|>=|<=|==|!=)\\s*([\\d.]+)");

    public void evaluate(MetricEvent event) {
        List<AlertRule> rules = alertRuleRepository.findByAppAppidAndStatus(event.getAppid(), "enabled");
        if (rules.isEmpty()) {
            log.debug("[spring-watch: 告警评估跳过 - appid={} 无启用规则]", event.getAppid());
            return;
        }
        log.debug("[spring-watch: 告警评估开始 - appid={}, metric={}, value={}, 待评估规则数={}]",
                event.getAppid(), event.getMetricName(), event.getValue(), rules.size());

        for (AlertRule rule : rules) {
            if (!"metric".equals(rule.getRuleType())) {
                continue;
            }
            if (matchRule(rule, event)) {
                boolean alreadyFired = alertWindowManager.isAlreadyFired(rule.getId(), event.getTimestamp());
                if (!alreadyFired) {
                    log.info("[spring-watch: 告警触发 - appid={}, rule={}, metric={}, value={}]",
                            event.getAppid(), rule.getRuleName(), event.getMetricName(), event.getValue());
                    alertNotifier.notify(rule, event);
                    alertWindowManager.recordFire(rule.getId(), event.getTimestamp());
                } else {
                    log.debug("[spring-watch: 告警抑制 - appid={}, rule={} 已在窗口期内触发]",
                            event.getAppid(), rule.getRuleName());
                }
            }
        }
    }

    private boolean matchRule(AlertRule rule, MetricEvent event) {
        if (rule.getExpression() == null || rule.getExpression().isBlank()) {
            if (rule.getThresholdValue() != null && event.getValue() != null) {
                return event.getValue() > rule.getThresholdValue();
            }
            return false;
        }

        Matcher matcher = EXPRESSION_PATTERN.matcher(rule.getExpression().trim());
        if (!matcher.matches()) {
            if (rule.getThresholdValue() != null && event.getValue() != null) {
                return event.getValue() > rule.getThresholdValue();
            }
            return false;
        }

        String metricName = matcher.group(1);
        String operator = matcher.group(2);
        double threshold = Double.parseDouble(matcher.group(3));

        if (!metricName.equals(event.getMetricName())) {
            return false;
        }

        double value = event.getValue() != null ? event.getValue() : 0;
        return switch (operator) {
            case ">" -> value > threshold;
            case "<" -> value < threshold;
            case ">=" -> value >= threshold;
            case "<=" -> value <= threshold;
            case "==" -> value == threshold;
            case "!=" -> value != threshold;
            default -> false;
        };
    }
}