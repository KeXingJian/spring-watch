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

    /**
     * P1-3: 复用 MapContext，避免每次评估分配新的 Map+底层数组。
     * 告警评估通常在虚拟线程或固定线程上执行，ThreadLocal 不会跨线程泄漏。
     */
    private static final ThreadLocal<MapContext> CTX = ThreadLocal.withInitial(MapContext::new);

    public boolean evaluate(String expression, MetricEvent event) {
        if (expression == null || expression.isBlank() || event == null) {
            log.debug("[Alerter] JEXL evaluate 跳过 - expression={}, event={}", expression, event);
            return false;
        }
        try {
            JexlExpression expr = jexlEngine.createExpression(expression);
            MapContext ctx = CTX.get();
            ctx.clear();
            ctx.set("value", event.getValue());
            ctx.set("metric", event.getMetricName());
            ctx.set("__app__", event.getAppid() != null ? String.valueOf(event.getAppid()) : "");
            ctx.set("__count__", event.getCount());
            if (event.getTags() != null) {
                event.getTags().forEach(ctx::set);
            }
            Object result = expr.evaluate(ctx);
            boolean boolResult = Boolean.TRUE.equals(result);
            log.debug("[Alerter] JEXL evaluate - expression={}, value={}, metric={}, result={}",
                    expression, event.getValue(), event.getMetricName(), boolResult);
            return boolResult;
        } catch (JexlException e) {
            log.warn("[Alerter] JEXL 表达式执行失败 - expr={}, error={}", expression, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("[Alerter] JEXL 表达式执行异常 - expr={}, error={}", expression, e.getMessage(), e);
            return false;
        }
    }
}
