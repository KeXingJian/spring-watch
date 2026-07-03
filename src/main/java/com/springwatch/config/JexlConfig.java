package com.springwatch.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlFeatures;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
public class JexlConfig {

    @Bean
    public JexlEngine jexlEngine() {
        JexlFeatures features = new JexlFeatures()
                .annotation(false)
                .loops(false)
                .lambda(false)
                .methodCall(false)
                .newInstance(false)
                .pragma(false)
                .register(false);

        JexlEngine engine = new JexlBuilder()
                .charset(StandardCharsets.UTF_8)
                .cache(256)
                .features(features)
                .create();

        log.info("[kxj: JexlEngine 初始化 - 沙箱化: loops/lambda/methodCall/newInstance/annotation/pragma/register 全部禁用]");
        return engine;
    }
}
