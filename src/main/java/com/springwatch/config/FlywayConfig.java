package com.springwatch.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Slf4j
@Configuration
public class FlywayConfig {

    @Bean
    public Flyway flyway(DataSource dataSource) {
        log.info("[spring-watch: Flyway 数据库迁移开始]");
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .mixed(true)
                .load();
        flyway.repair();
        flyway.migrate();
        log.info("[spring-watch: Flyway 数据库迁移完成]");
        return flyway;
    }
}
