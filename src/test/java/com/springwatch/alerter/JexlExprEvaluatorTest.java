package com.springwatch.alerter;

import com.springwatch.config.JexlConfig;
import com.springwatch.model.event.MetricEvent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class JexlExprEvaluatorTest {

    private final JexlConfig jexlConfig = new JexlConfig();
    private final JexlExprEvaluator evaluator = new JexlExprEvaluator(jexlConfig.jexlEngine());

    @Test
    void singleGreaterThan_breached() {
        assertTrue(evaluator.evaluate("value > 80", event(85.0)));
    }

    @Test
    void singleGreaterThan_notBreached() {
        assertFalse(evaluator.evaluate("value > 80", event(75.0)));
    }

    @Test
    void andCondition_insideBand_breached() {
        assertTrue(evaluator.evaluate("value > 80 && value < 95", event(85.0)));
    }

    @Test
    void andCondition_outsideBand_notBreached() {
        assertFalse(evaluator.evaluate("value > 80 && value < 95", event(70.0)));
        assertFalse(evaluator.evaluate("value > 80 && value < 95", event(100.0)));
    }

    @Test
    void orCondition_breached() {
        assertTrue(evaluator.evaluate("value > 100 || value < 0", event(150.0)));
        assertTrue(evaluator.evaluate("value > 100 || value < 0", event(-5.0)));
    }

    @Test
    void orCondition_notBreached() {
        assertFalse(evaluator.evaluate("value > 100 || value < 0", event(50.0)));
    }

    @Test
    void metricNameMatch_breached() {
        assertTrue(evaluator.evaluate("metric == 'jvm_heap' && value > 80", event(85.0, "jvm_heap")));
    }

    @Test
    void metricNameMatch_notBreached() {
        assertFalse(evaluator.evaluate("metric == 'jvm_heap' && value > 80", event(85.0, "cpu")));
    }

    @Test
    void nullExpression_returnsFalse() {
        assertFalse(evaluator.evaluate(null, event(85.0)));
    }

    @Test
    void blankExpression_returnsFalse() {
        assertFalse(evaluator.evaluate("   ", event(85.0)));
    }

    @Test
    void nullEvent_returnsFalse() {
        assertFalse(evaluator.evaluate("value > 0", null));
    }

    @Test
    void sandbox_methodCallRejected() {
        assertFalse(evaluator.evaluate("value.toString().length() > 0", event(85.0)));
    }

    @Test
    void sandbox_newInstanceRejected() {
        assertFalse(evaluator.evaluate("new('java.lang.String', 'x')", event(85.0)));
    }

    @Test
    void sandbox_lambdaRejected() {
        assertFalse(evaluator.evaluate("x -> x > 0", event(85.0)));
    }

    @Test
    void invalidExpression_returnsFalse() {
        assertFalse(evaluator.evaluate("!!!invalid(((", event(85.0)));
    }

    @Test
    void tagsAccessible() {
        MetricEvent e = MetricEvent.builder()
                .appid(1L).metricName("http").value(100.0)
                .timestamp(Instant.now())
                .tags(java.util.Map.of("method", "GET"))
                .build();
        assertTrue(evaluator.evaluate("method == 'GET' && value > 50", e));
        assertFalse(evaluator.evaluate("method == 'POST' && value > 50", e));
    }

    @Test
    void nullValueInEvent_treatedAsNull() {
        MetricEvent e = MetricEvent.builder()
                .appid(1L).metricName("x").value(null)
                .timestamp(Instant.now()).build();
        assertFalse(evaluator.evaluate("value > 80", e));
        assertFalse(evaluator.evaluate("value < 80", e));
    }

    private MetricEvent event(Double value) {
        return event(value, "test_metric");
    }

    private MetricEvent event(Double value, String metric) {
        return MetricEvent.builder()
                .appid(1L)
                .metricName(metric)
                .value(value)
                .timestamp(Instant.now())
                .build();
    }
}
