package com.springwatch.collector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OtelConfigGenerator {

    @Value("${spring-watch.agent.prometheus-host:0.0.0.0}")
    private String prometheusHost;

    public Map<String, String> generateOtelConfig(String appName, int metricsPort) {
        log.info("[spring-watch: 生成OTel Agent配置 - app={}, metricsPort={}, 模式=Agent暴露Prometheus端点]",
                appName, metricsPort);

        Map<String, String> config = new LinkedHashMap<>();
        config.put("OTEL_SERVICE_NAME", appName);
        config.put("OTEL_RESOURCE_ATTRIBUTES", "service.name=" + appName + ",service.namespace=spring-watch");
        config.put("OTEL_METRICS_EXPORTER", "prometheus");
        config.put("OTEL_LOGS_EXPORTER", "none");
        config.put("OTEL_TRACES_EXPORTER", "none");
        config.put("OTEL_EXPORTER_PROMETHEUS_HOST", prometheusHost);
        config.put("OTEL_EXPORTER_PROMETHEUS_PORT", String.valueOf(metricsPort));
        return config;
    }

    public String generateOtelAgentCommand(String agentJarPath, String appName, int metricsPort) {
        Map<String, String> config = generateOtelConfig(appName, metricsPort);
        StringBuilder sb = new StringBuilder();
        sb.append("-javaagent:").append(agentJarPath);
        for (Map.Entry<String, String> entry : config.entrySet()) {
            sb.append(" -D").append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }
}
