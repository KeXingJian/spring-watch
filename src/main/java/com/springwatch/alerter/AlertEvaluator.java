package com.springwatch.alerter;

import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.LogEvent;
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

    public enum BreachResult {
        BREACHED, NOT_BREACHED, NOT_APPLICABLE
    }

    private static final Pattern SIMPLE_EXPR =
            Pattern.compile("^(\\w[\\w.]*)\\s*(>=|<=|>|<|==|!=)\\s*([\\d.]+)$");

    private static final Pattern KEYWORD_EXPR =
            Pattern.compile("^keyword\\s*=\\s*\"?([^\"]+?)\"?\\s*$", Pattern.CASE_INSENSITIVE);

    private final JexlExprEvaluator jexlEvaluator;

    public boolean isBreached(AlertRule rule, MetricEvent event) {
        return evaluate(rule, event) == BreachResult.BREACHED;
    }

    public BreachResult evaluate(AlertRule rule, MetricEvent event) {
        if (rule == null || event == null) {
            log.debug("[Alerter] evaluate 跳过 - rule={}, event={}", rule, event);
            return BreachResult.NOT_APPLICABLE;
        }
        String type = rule.getRuleType();
        if ("log_error_rate".equals(type)) {
            if (!"log_error_rate".equals(event.getMetricName())) {
                return BreachResult.NOT_APPLICABLE;
            }
            boolean r = evaluateLogErrorRate(rule, event);
            log.debug("[Alerter] log_error_rate 评估 - ruleId={}, value={}, threshold={}, breached={}",
                    rule.getId(), event.getValue(), rule.getThresholdValue(), r);
            return r ? BreachResult.BREACHED : BreachResult.NOT_BREACHED;
        }
        if (!"metric".equals(type)) {
            log.debug("[Alerter] 非metric/log_error_rate类型, 跳过 - ruleId={}, type={}", rule.getId(), type);
            return BreachResult.NOT_APPLICABLE;
        }
        if (rule.getExpression() == null || rule.getExpression().isBlank()) {
            log.warn("[Alerter] metric规则表达式为空, 跳过 - ruleId={}, appid={}", rule.getId(), event.getAppid());
            return BreachResult.NOT_APPLICABLE;
        }

        String expr = rule.getExpression().trim();
        BreachResult result;
        if (SIMPLE_EXPR.matcher(expr).matches()) {
            result = simpleEvaluate(expr, event);
        } else {
            boolean jexl = jexlEvaluator.evaluate(expr, event);
            result = jexl ? BreachResult.BREACHED : BreachResult.NOT_BREACHED;
        }
        log.debug("[Alerter] metric规则评估 - ruleId={}, appid={}, metric={}, value={}, expression={}, result={}",
                rule.getId(), event.getAppid(), event.getMetricName(), event.getValue(), expr, result);
        return result;
    }

    /**
     * kxj: log_error_rate规则评估-与thresholdValue比较,百分比阈值
     */
    private boolean evaluateLogErrorRate(AlertRule rule, MetricEvent event) {
        if (!"log_error_rate".equals(event.getMetricName())) {
            return false;
        }
        Double value = event.getValue();
        if (value == null) {
            return false;
        }
        Double threshold = rule.getThresholdValue();
        if (threshold == null) {
            return false;
        }
        return value > threshold;
    }

    /**
     * kxj: 日志规则评估-log_keyword在message/throwable中查找关键字
     */
    public boolean isLogBreached(AlertRule rule, LogEvent event) {
        if (rule == null || event == null) {
            log.debug("[Alerter] isLogBreached 跳过 - rule={}, event={}", rule, event);
            return false;
        }
        if (!"log_keyword".equals(rule.getRuleType())) {
            log.debug("[Alerter] 非log_keyword类型, 跳过 - ruleId={}, type={}", rule.getId(), rule.getRuleType());
            return false;
        }
        String keyword = extractKeyword(rule.getExpression());
        if (keyword == null || keyword.isEmpty()) {
            log.warn("[Alerter] log_keyword规则keyword为空, 跳过 - ruleId={}, expression={}", rule.getId(), rule.getExpression());
            return false;
        }
        boolean inMessage = event.getMessage() != null && event.getMessage().contains(keyword);
        boolean inThrowable = event.getThrowable() != null && event.getThrowable().contains(keyword);
        boolean breached = inMessage || inThrowable;
        log.debug("[Alerter] log_keyword匹配 - ruleId={}, appid={}, keyword={}, inMessage={}, inThrowable={}, breached={}",
                rule.getId(), event.getAppid(), keyword, inMessage, inThrowable, breached);
        return breached;
    }

    public String extractKeyword(String expression) {
        if (expression == null) {
            return null;
        }
        String trim = expression.trim();
        if (trim.isEmpty()) {
            return null;
        }
        Matcher m = KEYWORD_EXPR.matcher(trim);
        if (m.matches()) {
            return m.group(1).trim();
        }
        return trim;
    }

    private BreachResult simpleEvaluate(String expr, MetricEvent event) {
        Matcher m = SIMPLE_EXPR.matcher(expr);
        if (!m.matches()) {
            return BreachResult.NOT_APPLICABLE;
        }
        String metricName = m.group(1);
        String op = m.group(2);
        double threshold = Double.parseDouble(m.group(3));

        if (event.getMetricName() == null || !metricName.equals(event.getMetricName())) {
            log.trace("[Alerter] simpleEvaluate 指标名不匹配 - expect={}, actual={}", metricName, event.getMetricName());
            return BreachResult.NOT_APPLICABLE;
        }
        Double value = event.getValue();
        if (value == null) {
            return BreachResult.NOT_BREACHED;
        }
        boolean cmp = switch (op) {
            case ">"  -> value >  threshold;
            case "<"  -> value <  threshold;
            case ">=" -> value >= threshold;
            case "<=" -> value <= threshold;
            case "==" -> value == threshold;
            case "!=" -> value != threshold;
            default   -> false;
        };
        BreachResult result = cmp ? BreachResult.BREACHED : BreachResult.NOT_BREACHED;
        log.debug("[Alerter] simpleEvaluate - metric={}, op={}, threshold={}, value={}, result={}",
                metricName, op, threshold, value, result);
        return result;
    }
}
