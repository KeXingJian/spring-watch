package com.springwatch.alerter;

import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.MetricEvent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class AlertEvaluatorTest {

    private final com.springwatch.config.JexlConfig jexlConfig = new com.springwatch.config.JexlConfig();
    private final JexlExprEvaluator jexlEvaluator = new JexlExprEvaluator(jexlConfig.jexlEngine());
    private final AlertEvaluator evaluator = new AlertEvaluator(jexlEvaluator);

    @Test
    void greaterThan_breached() {
        AlertRule rule = ruleOf("heap > 80");
        MetricEvent event = event("heap", 85.0);
        assertTrue(evaluator.isBreached(rule, event));
    }

    @Test
    void greaterThan_notBreached() {
        AlertRule rule = ruleOf("heap > 80");
        MetricEvent event = event("heap", 75.0);
        assertFalse(evaluator.isBreached(rule, event));
    }

    @Test
    void greaterEqual_boundaryMet() {
        assertTrue(evaluator.isBreached(ruleOf("x >= 5"), event("x", 5.0)));
        assertFalse(evaluator.isBreached(ruleOf("x >= 5"), event("x", 4.999)));
    }

    @Test
    void lessThan() {
        assertTrue(evaluator.isBreached(ruleOf("y < 10"), event("y", 9.0)));
        assertFalse(evaluator.isBreached(ruleOf("y < 10"), event("y", 10.0)));
    }

    @Test
    void lessEqual_boundaryMet() {
        assertTrue(evaluator.isBreached(ruleOf("y <= 10"), event("y", 10.0)));
        assertFalse(evaluator.isBreached(ruleOf("y <= 10"), event("y", 10.001)));
    }

    @Test
    void equal() {
        assertTrue(evaluator.isBreached(ruleOf("z == 5"), event("z", 5.0)));
        assertFalse(evaluator.isBreached(ruleOf("z == 5"), event("z", 4.999)));
    }

    @Test
    void notEqual() {
        assertTrue(evaluator.isBreached(ruleOf("z != 5"), event("z", 4.0)));
        assertFalse(evaluator.isBreached(ruleOf("z != 5"), event("z", 5.0)));
    }

    @Test
    void metricMismatch_returnsFalse() {
        AlertRule rule = ruleOf("heap > 80");
        MetricEvent event = event("cpu", 95.0);
        assertFalse(evaluator.isBreached(rule, event));
    }

    @Test
    void nullValue_returnsFalse() {
        AlertRule rule = ruleOf("x > 5");
        MetricEvent event = event("x", null);
        assertFalse(evaluator.isBreached(rule, event));
    }

    @Test
    void nullMetricName_returnsFalse() {
        AlertRule rule = ruleOf("x > 5");
        MetricEvent event = event(null, 100.0);
        assertFalse(evaluator.isBreached(rule, event));
    }

    @Test
    void invalidExpression_returnsFalse() {
        AlertRule rule = ruleOf("invalid expr");
        MetricEvent event = event("x", 5.0);
        assertFalse(evaluator.isBreached(rule, event));
    }

    @Test
    void blankExpression_returnsFalse() {
        AlertRule rule = ruleOf("   ");
        MetricEvent event = event("x", 5.0);
        assertFalse(evaluator.isBreached(rule, event));
    }

    @Test
    void nonMetricRuleType_returnsFalse() {
        AlertRule rule = AlertRule.builder()
                .ruleType("log")
                .expression("x > 5")
                .build();
        MetricEvent event = event("x", 10.0);
        assertFalse(evaluator.isBreached(rule, event));
    }

    @Test
    void metricNameWithDot() {
        AlertRule rule = ruleOf("jvm.memory.used > 80");
        MetricEvent event = event("jvm.memory.used", 90.0);
        assertTrue(evaluator.isBreached(rule, event));
    }

    @Test
    void decimalThreshold() {
        assertTrue(evaluator.isBreached(ruleOf("cpu > 0.8"), event("cpu", 0.9)));
        assertFalse(evaluator.isBreached(ruleOf("cpu > 0.8"), event("cpu", 0.7)));
    }

    private AlertRule ruleOf(String expr) {
        return AlertRule.builder()
                .ruleType("metric")
                .expression(expr)
                .build();
    }

    private MetricEvent event(String metric, Double value) {
        return MetricEvent.builder()
                .appid(1L)
                .metricName(metric)
                .value(value)
                .timestamp(Instant.now())
                .build();
    }
}
