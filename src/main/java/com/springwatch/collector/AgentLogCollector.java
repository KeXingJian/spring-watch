package com.springwatch.collector;

import com.springwatch.inflight.InflightProducerBridge;
import com.springwatch.model.event.LogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 拉取 Agent 日志并入 InflightQueue(自研,替代 Kafka)。
 * P0-2: 改用 InputStream 替代 ofByteArray,避免全 body 入堆
 * P1-2: 内部 JsonParser 逐条流式解析
 * P2-1: 返回 Result(ok + latestTimestamp + latencyMs + error),失败/成功可区分
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLogCollector {

    private final InflightProducerBridge inflightProducerBridge;
    private final ObjectMapper objectMapper;
    private final AgentHttpClient agentHttpClient;

    public Result collect(Long appid, String appName, String endpoint, Instant since, int readTimeoutMs) {
        long start = System.nanoTime();
        String url = buildUrl(endpoint, since);
        String remoteHost = parseHost(endpoint);
        long latencyMs = 0;

        AgentHttpClient.Result httpResult = agentHttpClient.get(url, readTimeoutMs);


        if (!httpResult.isOk()) {
            log.warn("[kxj: Agent日志拉取失败 - appid={}, app={}, url={}, error={}, latencyMs={}]",
                    appid, appName, url, httpResult.error(), latencyMs);
            return Result.failed(httpResult.error(), latencyMs);
        }
        if (httpResult.status() != 200) {
            log.warn("[kxj: Agent日志拉取非200 - appid={}, app={}, url={}, status={}, latencyMs={}]",
                    appid, appName, url, httpResult.status(), latencyMs);
            return Result.failed("non2xx:" + httpResult.status(), latencyMs);
        }
        InputStream body = httpResult.body();
        if (body == null) {
            return Result.failed("empty_body", latencyMs);
        }

        Instant latest = since;
        List<LogEvent> events = new ArrayList<>(256);
        try (InputStream in = body; JsonParser p = objectMapper.createParser(in)) {
            if (p.nextToken() != JsonToken.START_ARRAY) {
                log.warn("[kxj: Agent日志响应非数组 - appid={}, app={}, latencyMs={}]",
                        appid, appName, latencyMs);
                return Result.failed("not_array", latencyMs);
            }
            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (p.currentToken() != JsonToken.START_OBJECT) {
                    log.warn("[kxj: Agent日志元素非对象 - appid={}, app={}, token={}]",
                            appid, appName, p.currentToken());
                    p.skipChildren();
                    continue;
                }
                LogEvent event = p.readValueAs(LogEvent.class);
                if (event.getAppid() == null) {
                    event.setAppid(appid);
                }
                if (event.getTimestamp() == null) {
                    event.setTimestamp(Instant.now());
                }
                if (event.getHost() == null && remoteHost != null) {
                    event.setHost(remoteHost);
                }
                events.add(event);
                if (event.getTimestamp().isAfter(latest)) {
                    latest = event.getTimestamp();
                }
            }
            latencyMs = (System.nanoTime() - start) / 1_000_000L;
        } catch (Exception e) {
            log.warn("[kxj: Agent日志解析失败 - appid={}, app={}, error={}, latencyMs={}]",
                    appid, appName, e.getMessage(), latencyMs);
            return Result.failed("parse:" + e.getClass().getSimpleName(), latencyMs);
        }
        if (!events.isEmpty()) {
            int accepted = inflightProducerBridge.sendLogBatch(events);
            if (accepted < events.size()) {
                log.warn("[kxj: 批量入队部分/全部被拒 - appid={}, size={}, accepted={}]",
                    appid, events.size(), accepted);
            }
        }

        return Result.ok(latest, latencyMs);
    }

    public record Result(boolean ok, Instant latestTimestamp, long latencyMs, String error) {
        public static Result ok(Instant latest, long latencyMs) {
            return new Result(true, latest, latencyMs, null);
        }
        public static Result failed(String error, long latencyMs) {
            return new Result(false, null, latencyMs, error);
        }
    }

    private String buildUrl(String endpoint, Instant since) {
        String base = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        return normalizeBaseUrl(base) + "/api/agent/logs?since=" + since.toString();
    }

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
