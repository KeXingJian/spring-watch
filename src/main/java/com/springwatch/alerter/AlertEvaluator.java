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
        List<AlertRule> rules = alertRuleRepository.findByAppAppNameAndStatus(event.getAppName(), "enabled");
        if (rules.isEmpty()) {
            return;
        }

        for (AlertRule rule : rules) {
            if (!"metric".equals(rule.getRuleType())) {
                continue;
            }
            if (matchRule(rule, event)) {
                boolean alreadyFired = alertWindowManager.isAlreadyFired(rule.getId(), event.getTimestamp());
                if (!alreadyFired) {
                    log.info("[spring-watch: 告警触发 - app={}, rule={}, metric={}, value={}]",
                            event.getAppName(), rule.getRuleName(), event.getMetricName(), event.getValue());
                    alertNotifier.notify(rule, event);
                    alertWindowManager.recordFire(rule.getId(), event.getTimestamp());
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