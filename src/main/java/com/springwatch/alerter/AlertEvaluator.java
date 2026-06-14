package com.springwatch.alerter;

import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEvaluator {

    private static final Pattern SIMPLE_EXPR =
            Pattern.compile("^(\\w[\\w.]*)\\s*(>=|<=|>|<|==|!=)\\s*([\\d.]+)$");

    private final JexlExprEvaluator jexlEvaluator;

    public boolean isBreached(AlertRule rule, MetricEvent event) {
        if (rule == null || event == null) {
            return false;
        }
        if (!"metric".equals(rule.getRuleType())) {
            return false;
        }
        if (rule.getExpression() == null || rule.getExpression().isBlank()) {
            return false;
        }

        String expr = rule.getExpression().trim();

        if (SIMPLE_EXPR.matcher(expr).matches()) {
            return simpleEvaluate(expr, event);
        }
        return jexlEvaluator.evaluate(expr, event);
    }

    private boolean simpleEvaluate(String expr, MetricEvent event) {
        Matcher m = SIMPLE_EXPR.matcher(expr);
        if (!m.matches()) {
            return false;
        }
        String metricName = m.group(1);
        String op = m.group(2);
        double threshold = Double.parseDouble(m.group(3));

        if (event.getMetricName() == null || !metricName.equals(event.getMetricName())) {
            return false;
        }
        Double value = event.getValue();
        if (value == null) {
            return false;
        }
        return switch (op) {
            case ">"  -> value >  threshold;
            case "<"  -> value <  threshold;
            case ">=" -> value >= threshold;
            case "<=" -> value <= threshold;
            case "==" -> value == threshold;
            case "!=" -> value != threshold;
            default   -> false;
        };
    }
}
