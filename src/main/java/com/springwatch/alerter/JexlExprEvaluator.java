package com.springwatch.alerter;

import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JexlExprEvaluator {

    private final JexlEngine jexlEngine;

    public boolean evaluate(String expression, MetricEvent event) {
        if (expression == null || expression.isBlank() || event == null) {
            return false;
        }
        try {
            JexlExpression expr = jexlEngine.createExpression(expression);
            JexlContext ctx = new MapContext();
            ctx.set("value", event.getValue());
            ctx.set("metric", event.getMetricName());
            ctx.set("__app__", event.getAppid() != null ? String.valueOf(event.getAppid()) : "");
            ctx.set("__count__", event.getCount());
            if (event.getTags() != null) {
                event.getTags().forEach(ctx::set);
            }
            Object result = expr.evaluate(ctx);
            return Boolean.TRUE.equals(result);
        } catch (JexlException e) {
            log.warn("[Alerter] JEXL 表达式执行失败 - expr={}, error={}", expression, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("[Alerter] JEXL 表达式执行异常 - expr={}, error={}", expression, e.getMessage(), e);
            return false;
        }
    }
}
