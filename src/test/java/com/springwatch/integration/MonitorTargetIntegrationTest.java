package com.springwatch.integration;

import com.springwatch.collector.AgentMetricsCollector;
import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.dto.AppRegisterRequest;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.MonitorAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestConstructor;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class MonitorTargetIntegrationTest {

    @LocalServerPort
    private int port;

    private final MonitorAppRepository monitorAppRepository;
    private final AgentMetricsCollector agentMetricsCollector;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        this.restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @AfterEach
    void tearDown() {
    }

    private AppRegisterRequest buildMockTestRequest() {
        return AppRegisterRequest.builder()
                .appName("mock-test")
                .endpoint("http://localhost:8081")
                .metricsPort(9464)
                .appType("springboot")
                .scrapeInterval(15)
                .labels("env=docker,group=integration")
                .build();
    }

    @Test
    void registerMockTestAsMonitoringTarget() {
        AppRegisterRequest request = buildMockTestRequest();

        ResponseEntity<ApiResponse<MonitorApp>> response = restClient.post()
                .uri("/api/apps")
                .body(request)
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});

        ApiResponse<MonitorApp> body = response.getBody();
        MonitorApp app = body != null ? body.getData() : null;
        log.info("[spring-watch: 集成测试 - 注册mock-test监控目标] status={}, id={}, appName={}, endpoint={}, metricsPort={}",
                response.getStatusCode().value(),
                app != null ? app.getId() : null,
                app != null ? app.getAppName() : null,
                app != null ? app.getEndpoint() : null,
                app != null ? app.getMetricsPort() : null);

        MonitorApp persisted = monitorAppRepository.findByAppName("mock-test").orElse(null);
        log.info("[spring-watch: 集成测试 - 数据库持久化检查] id={}, endpoint={}, metricsPort={}",
                persisted != null ? persisted.getId() : null,
                persisted != null ? persisted.getEndpoint() : null,
                persisted != null ? persisted.getMetricsPort() : null);
    }

    @Test
    void listActiveMonitoringTargets() {
        monitorAppRepository.save(MonitorApp.builder()
                .appName("mock-test")
                .endpoint("http://localhost:8081")
                .metricsPort(9464)
                .appType("springboot")
                .scrapeInterval(15)
                .labels("env=docker,group=integration")
                .status("active")
                .build());

        ResponseEntity<ApiResponse<List<MonitorApp>>> response = restClient.get()
                .uri("/api/apps/active")
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});

        ApiResponse<List<MonitorApp>> body = response.getBody();
        List<MonitorApp> activeApps = body != null ? body.getData() : null;
        log.info("[spring-watch: 集成测试 - 查询活跃监控目标] status={}, activeAppCount={}",
                response.getStatusCode().value(),
                activeApps != null ? activeApps.size() : 0);
        if (activeApps != null) {
            activeApps.forEach(a -> log.info("[spring-watch: 集成测试 - 活跃目标] appName={}, endpoint={}, status={}",
                    a.getAppName(), a.getEndpoint(), a.getStatus()));
        }
    }

    @Test
    void generateOtelConfigForMockTest() {
        MonitorApp saved = monitorAppRepository.save(MonitorApp.builder()
                .appName("mock-test")
                .endpoint("http://localhost:8081")
                .metricsPort(9464)
                .appType("springboot")
                .scrapeInterval(15)
                .status("active")
                .build());

        ResponseEntity<ApiResponse<Map<String, Object>>> response = restClient.get()
                .uri("/api/apps/{id}/otel-config", saved.getId())
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});

        ApiResponse<Map<String, Object>> body = response.getBody();
        Map<String, Object> config = body != null ? body.getData() : null;
        log.info("[spring-watch: 集成测试 - 生成OTel Agent配置] status={}, config={}",
                response.getStatusCode().value(), config);
    }

    @Test
    void pullAgentMetricsForMockTest() {
        AgentMetricsCollector.MonitorTarget target = new AgentMetricsCollector.MonitorTarget(
                "mock-test", "http://localhost:8081", 9464);

        agentMetricsCollector.collect(target);

        log.info("[spring-watch: 集成测试 - mock-test Agent拉取完成]");
    }
}
