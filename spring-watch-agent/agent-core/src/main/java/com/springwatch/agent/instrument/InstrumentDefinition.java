package com.springwatch.agent.instrument;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * 探测点定义(借鉴 OTel InstrumentationModule 抽象)。
 * <p>
 * 每个实现对应一个探测目标(注解方法、JDBC 调用等);由
 * {@link com.springwatch.agent.AgentInstaller} 集中装配。
 */
public interface InstrumentDefinition {

    /**
     * 描述符匹配,返回需要被织入的类。
     */
    ElementMatcher.Junction<TypeDescription> typeMatcher();

    /**
     * 注入到目标 AgentBuilder。
     */
    AgentBuilder apply(AgentBuilder builder);

    /**
     * 名称(用于日志与 /api/agent/capabilities)。
     */
    String name();
}
