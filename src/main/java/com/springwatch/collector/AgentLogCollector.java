package com.springwatch.collector;

import com.springwatch.model.event.LogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLogCollector {

    private final KafkaProducerBridge kafkaProducerBridge;
    private final ObjectMapper objectMapper;

    public Instant collect(Long appid, String appName, String endpoint, Instant since) {
        String url = buildUrl(endpoint, since);
        long start = System.nanoTime();

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            int statusCode = conn.getResponseCode();
            long costMs = (System.nanoTime() - start) / 1_000_000;

            if (statusCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                conn.disconnect();

                List<LogEvent> logs = objectMapper.readValue(sb.toString(), new TypeReference<>() {});

                Instant latest = since;
                int sent = 0;
                for (LogEvent event : logs) {
                    if (event.getAppid() == null) {
                        event.setAppid(appid);
                    }
                    if (event.getTimestamp() == null) {
                        event.setTimestamp(Instant.now());
                    }
                    kafkaProducerBridge.sendLog(event);
                    sent++;
                    if (event.getTimestamp().isAfter(latest)) {
                        latest = event.getTimestamp();
                    }
                }
                log.info("[spring-watch: Agent日志拉取 - appid={}, app={}, since={}, count={}, latest={}, cost={}ms]",
                        appid, appName, since, sent, latest, costMs);
                return latest;
            } else {
                log.warn("[spring-watch: Agent日志拉取非200 - appid={}, app={}, url={}, status={}, cost={}ms]",
                        appid, appName, url, statusCode, costMs);
                conn.disconnect();
            }
        } catch (Exception e) {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[spring-watch: Agent日志拉取失败 - appid={}, app={}, url={}, error={}, cost={}ms]",
                    appid, appName, url, e.getMessage(), costMs);
        }
        return since;
    }

    private String buildUrl(String endpoint, Instant since) {
        String base = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        return base + "/api/agent/logs?since=" + since.toString();
    }
}
