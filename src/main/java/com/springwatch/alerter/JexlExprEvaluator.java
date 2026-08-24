package com.springwatch.alerter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.springwatch.model.event.MetricEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class JexlExprEvaluator {

    private final JexlEngine jexlEngine;
    private final MeterRegistry meterRegistry;

    @Value("${spring-watch.alert.jexl.cache-size:1024}")
    private int cacheSize;

    @Value("${spring-watch.alert.jexl.cache-expire-minutes:60}")
    private long cacheExpireMinutes;

    /**
     * P1-3: 复用 MapContext，避免每次评估分配新的 Map+底层数组。
     * 告警评估通常在虚拟线程或固定线程上执行，ThreadLocal 不会跨线程泄漏。
     */
    private static final ThreadLocal<MapContext> CTX = ThreadLocal.withInitial(MapContext::new);

    /**
     * kxj: 表达式编译结果缓存,避免每个事件都 createExpression 重新解析。
     * 告警规则数量有限,按表达式字符串缓存,命中率接近 100%。
     */
    private Cache<String, JexlExpression> expressionCache;

    /**
     * M4-2: 复用次数计数,验证 P1-3 的实际效果。
     * 评估总次数 = contextReused(复用) + newInstance(未复用,理论上为 0)。
     */
    private Counter contextReusedCounter;

    private Counter cacheHitCounter;
    private Counter cacheMissCounter;

    @PostConstruct
    void initMetrics() {
        this.expressionCache = Caffeine.newBuilder()
                .maximumSize(cacheSize)
                .expireAfterAccess(Duration.ofMinutes(cacheExpireMinutes))
                .build();
        contextReusedCounter = Counter.builder("spring.watch.alerter.jexl.context.reused")
                .description("JEXL MapContext 复用次数(P1-3 优化效果)")
                .register(meterRegistry);
        cacheHitCounter = Counter.builder("spring.watch.alerter.jexl.expr.cache_hit")
                .description("JEXL 表达式编译缓存命中次数(命中免解析)")
                .register(meterRegistry);
        cacheMissCounter = Counter.builder("spring.watch.alerter.jexl.expr.cache_miss")
                .description("JEXL 表达式编译缓存未命中次数(首次或淘汰后重新编译)")
                .register(meterRegistry);
        Gauge.builder("spring.watch.alerter.jexl.expr.cache_size", expressionCache, c -> c.estimatedSize())
                .description("JEXL 表达式编译缓存当前 entry 数")
                .register(meterRegistry);
    }

    public boolean evaluate(String expression, MetricEvent event) {
        if (expression == null || expression.isBlank() || event == null) {
            log.debug("[Alerter] JEXL evaluate 跳过 - expression={}, event={}", expression, event);
            return false;
        }
        try {
            JexlExpression expr = expressionCache.getIfPresent(expression);
            if (expr == null) {
                cacheMissCounter.increment();
                expr = expressionCache.get(expression, k -> jexlEngine.createExpression(k));
            } else {
                cacheHitCounter.increment();
            }
            MapContext ctx = CTX.get();
            ctx.clear();
            contextReusedCounter.increment();
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
