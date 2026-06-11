package com.springwatch.collector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OtelConfigGenerator {

    public Map<String, String> generatePrometheusConfig(String appName, int metricsPort) {
        log.info("[spring-watch: 生成OTel Agent配置 - app={}, metricsPort={}, 模式=Prometheus暴露]", appName, metricsPort);

        Map<String, String> config = new LinkedHashMap<>();
        config.put("OTEL_SERVICE_NAME", appName);
        config.put("OTEL_RESOURCE_ATTRIBUTES", "service.name=" + appName + ",service.namespace=spring-watch");
        config.put("OTEL_METRICS_EXPORTER", "prometheus");
        config.put("OTEL_LOGS_EXPORTER", "none");
        config.put("OTEL_TRACES_EXPORTER", "none");
        config.put("OTEL_EXPORTER_PROMETHEUS_PORT", String.valueOf(metricsPort));
        config.put("OTEL_EXPORTER_PROMETHEUS_HOST", "0.0.0.0");
        return config;
    }

    public String generatePrometheusAgentCommand(String agentJarPath, String appName, int metricsPort) {
        Map<String, String> config = generatePrometheusConfig(appName, metricsPort);
        StringBuilder sb = new StringBuilder();
        sb.append("-javaagent:").append(agentJarPath);
        for (Map.Entry<String, String> entry : config.entrySet()) {
            sb.append(" -D").append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }
}