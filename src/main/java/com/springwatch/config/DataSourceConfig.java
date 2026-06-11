package com.springwatch.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class DataSourceConfig {

    public DataSourceConfig() {
        log.info("[spring-watch: DataSource 初始化 - PostgreSQL]");
    }
}