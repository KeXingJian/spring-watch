package com.springwatch.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * kxj: 采集层自监控 - 提供 SimpleMeterRegistry,避免引入 actuator 完整依赖
 * 用户后续可暴露 /actuator/metrics,本配置已为可观测性预埋
 */
@Configuration
public class MetricsConfig {

    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
