package com.springwatch.sdk.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级监控注解(自研,功能等价于 OpenTelemetry @WithSpan)。
 * <p>
 * Agent 通过描述符匹配识别本注解,无需在 Agent 中 import;
 * 可标注在类上(Agent 织入该类的全部方法)或方法上,客户可不引用 SDK 的
 * 情况下也可继续使用 @WithSpan,行为完全一致。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface SwMon {

    String DEFAULT_NAME = "";

    /**
     * 指标 method 标签值,默认 = 类#方法全名。
     */
    String value() default DEFAULT_NAME;

    /**
     * 是否记录入参与返回值,默认 false 防基数爆炸。
     */
    boolean recordArgs() default false;
}
