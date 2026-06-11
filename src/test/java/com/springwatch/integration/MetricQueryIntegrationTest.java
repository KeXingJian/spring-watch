package com.springwatch.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestConstructor;
import org.springframework.web.client.RestClient;

import com.springwatch.model.dto.ApiResponse;

import java.util.List;
import java.util.Map;

@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class MetricQueryIntegrationTest {

    @LocalServerPort
    private int port;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        this.restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void queryMetricsDefaultRange() {
        ResponseEntity<ApiResponse<List<Map<String, Object>>>> response = restClient.get()
                .uri("/api/metrics/query")
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});

        ApiResponse<List<Map<String, Object>>> body = response.getBody();
        List<Map<String, Object>> rows = body != null ? body.getData() : null;
        log.info("[spring-watch: 集成测试 - 时序指标查询(默认1h)] status={}, rows={}",
                response.getStatusCode().value(),
                rows != null ? rows.size() : 0);
        printRows(rows, 5);
    }

    @Test
    void queryMetricsByApp() {
        ResponseEntity<ApiResponse<List<Map<String, Object>>>> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/metrics/query")
                        .queryParam("app", "mock-test")
                        .queryParam("start", "-1h")
                        .queryParam("stop", "now()")
                        .build())
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});

        ApiResponse<List<Map<String, Object>>> body = response.getBody();
        List<Map<String, Object>> rows = body != null ? body.getData() : null;
        log.info("[spring-watch: 集成测试 - 时序指标查询(按app)] app=mock-test, status={}, rows={}",
                response.getStatusCode().value(),
                rows != null ? rows.size() : 0);
        printRows(rows, 5);
    }

    @Test
    void queryMetricsByAppAndMetric() {
        ResponseEntity<ApiResponse<List<Map<String, Object>>>> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/metrics/query")
                        .queryParam("app", "mock-test")
                        .queryParam("metric", "jvm_memory_used_bytes")
                        .queryParam("start", "-30m")
                        .queryParam("stop", "now()")
                        .build())
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});

        ApiResponse<List<Map<String, Object>>> body = response.getBody();
        List<Map<String, Object>> rows = body != null ? body.getData() : null;
        log.info("[spring-watch: 集成测试 - 时序指标查询(按app+metric)] app=mock-test, metric=jvm_memory_used_bytes, status={}, rows={}",
                response.getStatusCode().value(),
                rows != null ? rows.size() : 0);
        printRows(rows, 5);
    }

    @Test
    void queryMetricsByAbsoluteTimeRange() {
        String start = java.time.Instant.now().minusSeconds(3600).toString();
        String stop = java.time.Instant.now().toString();

        ResponseEntity<ApiResponse<List<Map<String, Object>>>> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/metrics/query")
                        .queryParam("start", start)
                        .queryParam("stop", stop)
                        .build())
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});

        ApiResponse<List<Map<String, Object>>> body = response.getBody();
        List<Map<String, Object>> rows = body != null ? body.getData() : null;
        log.info("[spring-watch: 集成测试 - 时序指标查询(绝对时间)] range={}~{}, status={}, rows={}",
                start, stop,
                response.getStatusCode().value(),
                rows != null ? rows.size() : 0);
        printRows(rows, 3);
    }

    private void printRows(List<Map<String, Object>> rows, int limit) {
        if (rows == null || rows.isEmpty()) {
            log.info("[spring-watch: 集成测试 - 无数据]");
            return;
        }
        int n = Math.min(limit, rows.size());
        for (int i = 0; i < n; i++) {
            log.info("[spring-watch: 集成测试 - row[{}]] {}", i, rows.get(i));
        }
        if (rows.size() > n) {
            log.info("[spring-watch: 集成测试 - 仅展示前{}条, 实际共{}条]", n, rows.size());
        }
    }
}
