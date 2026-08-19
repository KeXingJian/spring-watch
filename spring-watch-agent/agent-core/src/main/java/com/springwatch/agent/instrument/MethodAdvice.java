package com.springwatch.agent.instrument;

import com.springwatch.agent.metric.Counter;
import com.springwatch.agent.metric.Histogram;
import com.springwatch.agent.metric.Labels;
import com.springwatch.agent.metric.MetricRegistry;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 方法级监控 Advice(在目标方法的字节码中 inline 调用 enter/exit)。
 * <p>
 * 关键点:本类不 import {@code io.opentelemetry.instrumentation.annotations.WithSpan}
 * 也不 import {@code com.springwatch.sdk.annotation.SwMon},Agent 通过
 * 描述符匹配识别注解,本类只读 advice 上下文(@Origin + @This)。
 * <p>
 * Agent 与 Advice 之间通过静态字段传递 MetricRegistry / 容量上限。
 * 静态字段会被 ByteBuddy inline 复制到每个被织入的类,仅占 8+4 字节。
 */
public final class MethodAdvice {

    private MethodAdvice() {
    }

    public static final String COUNTER_NAME = "sw_method_calls_total";
    public static final String ERRORS_NAME = "sw_method_errors_total";
    public static final String DURATION_NAME = "sw_method_duration_seconds";

    private static volatile MetricRegistry REGISTRY;
    private static volatile int CARDINALITY_LIMIT = Integer.MAX_VALUE;
    private static final ConcurrentHashMap<String, LongAdder> EVICTED = new ConcurrentHashMap<>();

    public static void bind(MetricRegistry registry, int cardinalityLimit) {
        REGISTRY = registry;
        CARDINALITY_LIMIT = cardinalityLimit;
    }

    public static long evictedTotal() {
        long sum = 0L;
        for (LongAdder a : EVICTED.values()) sum += a.sum();
        return sum;
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long onEnter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin("#t#m") String origin,
                               @Advice.Thrown Throwable thrown,
                               @Advice.Enter long startNanos) {
        try {
            MetricRegistry registry = REGISTRY;
            if (registry == null) return;
            if (CARDINALITY_LIMIT > 0 && observedKeys(registry) > CARDINALITY_LIMIT) {
                EVICTED.computeIfAbsent(origin, k -> new LongAdder()).increment();
                return;
            }

            double durationSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            Labels label = Labels.of("method", origin);

            registry.counter(COUNTER_NAME, "Method invocations total").inc(label);
            if (thrown != null) {
                Labels errorLabel = Labels.of("method", origin, "error_type", thrown.getClass().getSimpleName());
                registry.counter(ERRORS_NAME, "Method errors total").inc(errorLabel);
            }
            registry.histogram(DURATION_NAME, "Method duration in seconds").observe(label, durationSec);
        } catch (Throwable ignore) {
        }
    }

    private static long observedKeys(MetricRegistry registry) {
        Counter c = registry.counters().get(COUNTER_NAME);
        return c == null ? 0L : c.cells().size();
    }
}
