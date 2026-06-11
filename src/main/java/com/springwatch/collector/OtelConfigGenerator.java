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

    @Value("${spring-watch.agent.otlp-endpoint:http://localhost:4317}")
    private String otlpEndpoint;

    @Value("${spring-watch.agent.otlp-protocol:grpc}")
    private String otlpProtocol;

    public Map<String, String> generateOtelConfig(String appName) {
        log.info("[spring-watch: 生成OTel Agent配置 - app={}, otlpEndpoint={}, protocol={}, 模式=OTLP推送]",
                appName, otlpEndpoint, otlpProtocol);

        Map<String, String> config = new LinkedHashMap<>();
        config.put("OTEL_SERVICE_NAME", appName);
        config.put("OTEL_RESOURCE_ATTRIBUTES", "service.name=" + appName + ",service.namespace=spring-watch");
        config.put("OTEL_METRICS_EXPORTER", "otlp");
        config.put("OTEL_LOGS_EXPORTER", "otlp");
        config.put("OTEL_TRACES_EXPORTER", "otlp");
        config.put("OTEL_EXPORTER_OTLP_ENDPOINT", otlpEndpoint);
        config.put("OTEL_EXPORTER_OTLP_PROTOCOL", otlpProtocol);
        return config;
    }

    public String generateOtelAgentCommand(String agentJarPath, String appName) {
        Map<String, String> config = generateOtelConfig(appName);
        StringBuilder sb = new StringBuilder();
        sb.append("-javaagent:").append(agentJarPath);
        for (Map.Entry<String, String> entry : config.entrySet()) {
            sb.append(" -D").append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }
}
