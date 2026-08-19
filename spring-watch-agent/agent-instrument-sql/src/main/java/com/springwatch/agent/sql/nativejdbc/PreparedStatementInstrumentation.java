package com.springwatch.agent.sql.nativejdbc;

import com.springwatch.agent.config.AgentConfig;
import com.springwatch.agent.instrument.InstrumentDefinition;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

/**
 * 织入 {@code java.sql.PreparedStatement} 的 execute / executeQuery /
 * executeUpdate / executeBatch / addBatch。无参方法,SQL 通过反射从驱动
 * 实现的私有字段抽(PreparedStatementAdvice)。
 */
public final class PreparedStatementInstrumentation implements InstrumentDefinition {

    @Override
    public String name() {
        return "java.sql.PreparedStatement";
    }

    @Override
    public ElementMatcher.Junction<TypeDescription> typeMatcher() {
        return ElementMatchers.hasSuperType(named("java.sql.PreparedStatement"));
    }

    @Override
    public AgentBuilder apply(AgentBuilder builder) {
        if (!AgentConfig.isSqlEnabled()) return builder;
        ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> methodMatcher = ElementMatchers
                .any()
                .and(named("execute").and(takesNoArguments()).and(isPublic()))
                .or(named("executeQuery").and(takesNoArguments()).and(isPublic()))
                .or(named("executeUpdate").and(takesNoArguments()).and(isPublic()))
                .or(named("executeLargeUpdate").and(takesNoArguments()).and(isPublic()))
                .or(named("addBatch").and(takesNoArguments()).and(isPublic()))
                .or(named("executeBatch").and(takesNoArguments()).and(isPublic()))
                .or(named("executeLargeBatch").and(takesNoArguments()).and(isPublic()));
        return builder
                .type(typeMatcher())
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(PreparedStatementAdvice.class).on(methodMatcher)));
    }
}
