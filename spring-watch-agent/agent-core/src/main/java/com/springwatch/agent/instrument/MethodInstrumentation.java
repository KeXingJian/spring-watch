package com.springwatch.agent.instrument;

import com.springwatch.agent.config.AgentConfig;
import com.springwatch.agent.metric.MetricRegistry;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * 方法级注入:按描述符匹配 @WithSpan / @SwMon,织入计数 + 耗时。
 * <p>
 * 关键点:不 import 注解类,只按 FQN 描述符字符串匹配,使 Agent 编译期
 * 不依赖 OTel annotations 与 spring-watch-sdk。
 */
public final class MethodInstrumentation implements InstrumentDefinition {

    private static final String DESC_OTEL_WITHSPAN = "io.opentelemetry.instrumentation.annotations.WithSpan";
    private static final String DESC_SWMON = "com.springwatch.sdk.annotation.SwMon";

    private final MetricRegistry registry;

    public MethodInstrumentation(MetricRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "method";
    }

    @Override
    public ElementMatcher.Junction<TypeDescription> typeMatcher() {
        return isAnnotatedWith(named(DESC_OTEL_WITHSPAN))
                .or(isAnnotatedWith(named(DESC_SWMON)));
    }

    @Override
    public AgentBuilder apply(AgentBuilder builder) {
        if (!AgentConfig.isMethodEnabled()) {
            return builder;
        }
        MethodAdvice.bind(registry, AgentConfig.methodCardinalityLimit());
        return builder
                .type(typeMatcher())
                .transform((builder1, typeDescription, classLoader, module, protectionDomain) ->
                        builder1.visit(Advice.to(MethodAdvice.class)
                                .on(ElementMatchers.any()
                                        .and(ElementMatchers.not(ElementMatchers.isConstructor())))));
    }
}
