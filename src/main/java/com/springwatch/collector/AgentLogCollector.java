package com.springwatch.collector;

import com.springwatch.model.event.LogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLogCollector {

    private final KafkaProducerBridge kafkaProducerBridge;
    private final ObjectMapper objectMapper;
    private final AgentHttpClient agentHttpClient;

    public Instant collect(Long appid, String appName, String endpoint, Instant since) {
        String url = buildUrl(endpoint, since);
        String remoteHost = parseHost(endpoint);

        AgentHttpClient.Result result = agentHttpClient.get(url);
        if (!result.isOk()) {
            log.warn("[spring-watch: Agent日志拉取失败 - appid={}, app={}, url={}, error={}]",
                    appid, appName, url, result.error());
            return since;
        }
        if (result.status() != 200) {
            log.warn("[spring-watch: Agent日志拉取非200 - appid={}, app={}, url={}, status={}]",
                    appid, appName, url, result.status());
            return since;
        }
        String body = result.body();
        if (body == null || body.isEmpty()) {
            return since;
        }
        List<LogEvent> logs;
        try {
            logs = objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[spring-watch: Agent日志解析失败 - appid={}, app={}, error={}]",
                    appid, appName, e.getMessage());
            return since;
        }
        if (logs == null) {
            logs = Collections.emptyList();
        }
        Instant latest = since;
        int sent = 0;
        for (LogEvent event : logs) {
            if (event.getAppid() == null) {
                event.setAppid(appid);
            }
            if (event.getTimestamp() == null) {
                event.setTimestamp(Instant.now());
            }
            if (event.getHost() == null && remoteHost != null) {
                event.setHost(remoteHost);
            }

            kafkaProducerBridge.sendLog(event);
            sent++;
            if (event.getTimestamp().isAfter(latest)) {
                latest = event.getTimestamp();
            }
        }
        log.info("[spring-watch: Agent日志拉取 - appid={}, app={}, since={}, count={}, latest={}]",
                appid, appName, since, sent, latest);
        return latest;
    }

    private String buildUrl(String endpoint, Instant since) {
        String base = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        return normalizeBaseUrl(base) + "/api/agent/logs?since=" + since.toString();
    }

    /**
     * kxj: 统一补全 scheme - 修复 "host:port" 形式 endpoint 缺 http:// 报 invalid URI scheme
     */
    private static String normalizeBaseUrl(String hostOrUrl) {
        if (hostOrUrl == null || hostOrUrl.isBlank()) {
            return "http://localhost";
        }
        String lower = hostOrUrl.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return hostOrUrl;
        }
        return "http://" + hostOrUrl;
    }

    private String parseHost(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return null;
        }
        try {
            return URI.create(endpoint).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
