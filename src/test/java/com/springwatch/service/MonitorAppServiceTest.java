package com.springwatch.service;

import com.springwatch.collector.OtelConfigGenerator;
import com.springwatch.model.dto.AppRegisterRequest;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.MonitorAppRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitorAppServiceTest {

    @Mock
    private MonitorAppRepository monitorAppRepository;

    @Mock
    private OtelConfigGenerator otelConfigGenerator;

    @InjectMocks
    private MonitorAppService monitorAppService;

    @AfterEach
    void tearDown() {
        reset(monitorAppRepository, otelConfigGenerator);
    }

    @Test
    void shouldRegisterMockTestTarget() {
        AppRegisterRequest request = AppRegisterRequest.builder()
                .appName("mock-test")
                .endpoint("http://localhost:8081")
                .collectMode("prometheus")
                .metricsPort(9464)
                .appType("springboot")
                .scrapeInterval(15)
                .build();

        when(monitorAppRepository.existsByAppName("mock-test")).thenReturn(false);
        when(monitorAppRepository.save(any(MonitorApp.class))).thenAnswer(inv -> {
            MonitorApp app = inv.getArgument(0);
            app.setId(1L);
            return app;
        });

        MonitorApp result = monitorAppService.register(request);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getAppName()).isEqualTo("mock-test");
        assertThat(result.getEndpoint()).isEqualTo("http://localhost:8081");
        assertThat(result.getCollectMode()).isEqualTo("prometheus");
        assertThat(result.getMetricsPort()).isEqualTo(9464);
        assertThat(result.getAppType()).isEqualTo("springboot");
        assertThat(result.getScrapeInterval()).isEqualTo(15);
        assertThat(result.getStatus()).isEqualTo("active");

        verify(monitorAppRepository).existsByAppName("mock-test");
        verify(monitorAppRepository).save(any(MonitorApp.class));
    }

    @Test
    void shouldThrowWhenRegisterDuplicateTarget() {
        AppRegisterRequest request = AppRegisterRequest.builder()
                .appName("mock-test")
                .endpoint("http://localhost:8081")
                .build();

        when(monitorAppRepository.existsByAppName("mock-test")).thenReturn(true);

        assertThatThrownBy(() -> monitorAppService.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mock-test");

        verify(monitorAppRepository, never()).save(any());
    }

    @Test
    void shouldListAllTargets() {
        MonitorApp app1 = MonitorApp.builder().id(1L).appName("mock-test").status("active").build();
        MonitorApp app2 = MonitorApp.builder().id(2L).appName("other-app").status("active").build();
        when(monitorAppRepository.findAll()).thenReturn(List.of(app1, app2));

        List<MonitorApp> result = monitorAppService.listAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getAppName()).isEqualTo("mock-test");
        assertThat(result.get(1).getAppName()).isEqualTo("other-app");
    }

    @Test
    void shouldListActiveTargets() {
        MonitorApp active = MonitorApp.builder().id(1L).appName("mock-test").status("active").build();
        when(monitorAppRepository.findByStatus("active")).thenReturn(List.of(active));

        List<MonitorApp> result = monitorAppService.listActive();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAppName()).isEqualTo("mock-test");
    }

    @Test
    void shouldGetTargetById() {
        MonitorApp app = MonitorApp.builder().id(1L).appName("mock-test").endpoint("http://localhost:8081").build();
        when(monitorAppRepository.findById(1L)).thenReturn(Optional.of(app));

        MonitorApp result = monitorAppService.getById(1L);

        assertThat(result.getAppName()).isEqualTo("mock-test");
        assertThat(result.getEndpoint()).isEqualTo("http://localhost:8081");
    }

    @Test
    void shouldThrowWhenTargetNotFoundById() {
        when(monitorAppRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> monitorAppService.getById(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    void shouldGetTargetByAppName() {
        MonitorApp app = MonitorApp.builder().id(1L).appName("mock-test").build();
        when(monitorAppRepository.findByAppName("mock-test")).thenReturn(Optional.of(app));

        MonitorApp result = monitorAppService.getByAppName("mock-test");

        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void shouldUpdateTarget() {
        MonitorApp existing = MonitorApp.builder()
                .id(1L).appName("mock-test").endpoint("http://localhost:8081")
                .collectMode("prometheus").scrapeInterval(15)
                .build();
        when(monitorAppRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(monitorAppRepository.save(any(MonitorApp.class))).thenReturn(existing);

        AppRegisterRequest update = AppRegisterRequest.builder()
                .endpoint("http://localhost:8082")
                .collectMode("both")
                .scrapeInterval(30)
                .build();

        MonitorApp result = monitorAppService.update(1L, update);

        assertThat(result.getEndpoint()).isEqualTo("http://localhost:8082");
        assertThat(result.getCollectMode()).isEqualTo("both");
        assertThat(result.getScrapeInterval()).isEqualTo(30);
        verify(monitorAppRepository).save(existing);
    }

    @Test
    void shouldDeleteTarget() {
        MonitorApp app = MonitorApp.builder().id(1L).appName("mock-test").build();
        when(monitorAppRepository.findById(1L)).thenReturn(Optional.of(app));

        monitorAppService.delete(1L);

        verify(monitorAppRepository).delete(app);
    }

    @Test
    void shouldGenerateOtelConfig() {
        MonitorApp app = MonitorApp.builder()
                .id(1L).appName("mock-test").endpoint("http://localhost:8081")
                .collectMode("prometheus").metricsPort(9464)
                .build();
        when(monitorAppRepository.findById(1L)).thenReturn(Optional.of(app));

        Map<String, String> mockEnv = Map.of(
                "OTEL_SERVICE_NAME", "mock-test",
                "OTEL_METRICS_EXPORTER", "prometheus",
                "OTEL_EXPORTER_PROMETHEUS_PORT", "9464"
        );
        when(otelConfigGenerator.generatePrometheusConfig("mock-test", 9464)).thenReturn(mockEnv);
        when(otelConfigGenerator.generatePrometheusAgentCommand(anyString(), eq("mock-test"), eq(9464)))
                .thenReturn("-javaagent:opentelemetry-javaagent.jar -DOTEL_SERVICE_NAME=mock-test");

        Map<String, Object> result = monitorAppService.generateOtelConfig(1L);

        assertThat(result.get("appName")).isEqualTo("mock-test");
        assertThat(result.get("metricsPort")).isEqualTo(9464);
        assertThat(result.get("environmentVariables")).isEqualTo(mockEnv);
        assertThat(result.get("javaAgentCommand")).asString().contains("mock-test");
    }

    @Test
    void shouldRegisterWithDefaultsWhenOptionalFieldsMissing() {
        AppRegisterRequest request = AppRegisterRequest.builder()
                .appName("mock-test")
                .endpoint("http://localhost:8081")
                .build();

        when(monitorAppRepository.existsByAppName("mock-test")).thenReturn(false);
        when(monitorAppRepository.save(any(MonitorApp.class))).thenAnswer(inv -> inv.getArgument(0));

        MonitorApp result = monitorAppService.register(request);

        assertThat(result.getCollectMode()).isEqualTo("prometheus");
        assertThat(result.getMetricsPort()).isEqualTo(9464);
        assertThat(result.getScrapeInterval()).isEqualTo(15);
        assertThat(result.getAppType()).isEqualTo("springboot");
        assertThat(result.getStatus()).isEqualTo("active");
    }
}
