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
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

/**
 * 织入 {@code java.sql.Statement} 的所有 execute/query/update/addBatch/executeBatch。
 * <p>
 * 子类继承匹配:纯 {@code implementsInterface(named("java.sql.Statement"))} 用于
 * 反射类;这里用 {@code named("java.sql.Statement")} 直接匹配,ByteBuddy 会沿
 * 继承链自动重写所有 Statement 子类(jdbc 驱动实现)。
 */
public final class StatementInstrumentation implements InstrumentDefinition {

    @Override
    public String name() {
        return "java.sql.Statement";
    }

    @Override
    public ElementMatcher.Junction<TypeDescription> typeMatcher() {
        return ElementMatchers.hasSuperType(named("java.sql.Statement"));
    }

    @Override
    public AgentBuilder apply(AgentBuilder builder) {
        if (!AgentConfig.isSqlEnabled()) return builder;
        ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> methodMatcher = ElementMatchers
                .any()
                .and(named("execute").and(takesArgument(0, String.class)).and(isPublic()))
                .or(named("executeQuery").and(takesArgument(0, String.class)).and(isPublic()))
                .or(named("executeUpdate").and(takesArgument(0, String.class)).and(isPublic()))
                .or(named("executeLargeUpdate").and(takesArgument(0, String.class)).and(isPublic()))
                .or(named("addBatch").and(takesArgument(0, String.class)).and(isPublic()))
                .or(named("executeBatch").and(takesNoArguments()).and(isPublic()))
                .or(named("executeLargeBatch").and(takesNoArguments()).and(isPublic()));
        return builder
                .type(typeMatcher())
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(StatementAdvice.class).on(methodMatcher)));
    }
}
