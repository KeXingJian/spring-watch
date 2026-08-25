package com.springwatch.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.InfluxDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AI 模块容器测试基类。
 * Testcontainers 动态拉起 PostgreSQL + InfluxDB。
 * PostgreSQL 走 @ServiceConnection 自动绑定数据源;
 * InfluxDB 无内建 ConnectionDetails,用 @DynamicPropertySource 手动绑定 influxdb.* 属性。
 * Flyway 在上下文启动时执行 V1~V19。不依赖宿主机已部署中间件,不依赖 mock-test。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class BaseIntegrationTest {

    @Container
    @ServiceConnection
    public static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("spring_collector")
            .withUsername("root")
            .withPassword("123456");

    @Container
    public static final InfluxDBContainer<?> IDB = new InfluxDBContainer<>(
            DockerImageName.parse("influxdb:2.7"))
            .withDatabase("metrics")
            .withUsername("admin")
            .withPassword("admin123456")
            .withAdminPassword("admin123456")
            .withOrganization("spring-watch")
            .withBucket("metrics")
            .withAdminToken("sw-token-2024");

    @DynamicPropertySource
    static void influxProps(DynamicPropertyRegistry registry) {
        registry.add("influxdb.url", IDB::getUrl);
        registry.add("influxdb.token", IDB::getAdminToken);
        registry.add("influxdb.org", () -> "spring-watch");
        registry.add("influxdb.metrics-bucket", () -> "metrics");
        registry.add("influxdb.log-bucket", () -> "logs");
        // 容器测试默认降级态:AI base-url 指向本地快速失败端口,LLM/embedding 不连外部
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:9");
        registry.add("spring.ai.openai.api-key", () -> "sk-test-invalid");
        // 测试上下文可能多个类串行共用容器,加大连接池避免瞬时竞争
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }
}