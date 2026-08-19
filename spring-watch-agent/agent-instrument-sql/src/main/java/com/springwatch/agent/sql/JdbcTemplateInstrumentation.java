package com.springwatch.agent.sql;

import com.springwatch.agent.config.AgentConfig;
import com.springwatch.agent.instrument.InstrumentDefinition;
import com.springwatch.agent.metric.MetricRegistry;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

/**
 * P1:织入 JdbcTemplate(系统类加载器,免 bootstrap 复杂度)。
 * <p>
 * 目标方法:query / queryForObject / queryForList / update / execute,
 * 第一个参数为 String SQL(或 StatementCreator,内部通过 Connection create);
 * digester 只处理 String 形式,非 String 跳过维度计数。
 */
public final class JdbcTemplateInstrumentation implements InstrumentDefinition {

    private static final String TYPE = "org.springframework.jdbc.core.JdbcTemplate";

    private final MetricRegistry registry;

    public JdbcTemplateInstrumentation(MetricRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "spring-jdbc";
    }

    @Override
    public ElementMatcher.Junction<TypeDescription> typeMatcher() {
        return named(TYPE);
    }

    @Override
    public AgentBuilder apply(AgentBuilder builder) {
        if (!AgentConfig.isSqlEnabled()) return builder;
        SqlAdvice.bind(registry, AgentConfig.sqlSlowMs(), AgentConfig.sqlDigestLimit());
        return builder
                .type(typeMatcher())
                .transform((b, td, cl, m, pd) -> b.visit(
                        net.bytebuddy.asm.Advice.to(SqlAdvice.class)
                                .on(named("query").and(takesArgument(0, String.class))
                                        .or(named("queryForObject").and(takesArgument(0, String.class)))
                                        .or(named("queryForList").and(takesArgument(0, String.class)))
                                        .or(named("update").and(takesArgument(0, String.class)))
                                        .or(named("execute").and(takesArgument(0, String.class))))));
    }
}
