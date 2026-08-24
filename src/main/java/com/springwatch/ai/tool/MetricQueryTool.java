package com.springwatch.ai.tool;

import tools.jackson.databind.ObjectMapper;
import com.springwatch.service.MetricQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricQueryTool {

    private final MetricQueryService metricQueryService;
    private final ObjectMapper objectMapper;

    @Tool(description = "查询指定应用某个指标的最新值(近1小时),返回 value/时间/标签。指标名如 jvm_memory_used_bytes、http_server_requests_seconds_sum")
    public String latest(
            @ToolParam(description = "监控应用 appid") Long appid,
            @ToolParam(description = "指标名,如 jvm_memory_used_bytes") String metric,
            @ToolParam(description = "标签过滤,格式 k=v,k2=v2,可为空") String tagFilters) {
        log.info("[kxj: AI工具 指标latest - appid={}, metric={}]", appid, metric);
        Map<String, Object> result = metricQueryService.queryLatest(appid, metric, parseTags(tagFilters));
        return toJson(result);
    }

    @Tool(description = "查询指定应用某个指标在时间区间内的时序数据,返回聚合后的时间序列点。agg 支持 mean/max/min/sum/last/rate,every 采样步长如 30s/1m/5m")
    public String series(
            @ToolParam(description = "监控应用 appid") Long appid,
            @ToolParam(description = "指标名,如 jvm_memory_used_bytes") String metric,
            @ToolParam(description = "起始时间 ISO-8601,如 2026-08-23T09:00:00Z,可为空(默认15分钟前)") String from,
            @ToolParam(description = "结束时间 ISO-8601,可为空(默认现在)") String to,
            @ToolParam(description = "聚合方式 mean/max/min/sum/last/rate,默认 mean") String agg,
            @ToolParam(description = "采样步长,如 30s/1m/5m,默认 30s") String every,
            @ToolParam(description = "标签过滤,格式 k=v,k2=v2,可为空") String tagFilters) {
        Instant fromInstant = parseInstant(from, Instant.now().minusSeconds(900));
        Instant toInstant = parseInstant(to, Instant.now());
        log.info("[kxj: AI工具 指标series - appid={}, metric={}, from={}, to={}, agg={}, every={}]",
                appid, metric, fromInstant, toInstant, agg, every);
        Map<String, Object> result = metricQueryService.querySeries(appid, metric, fromInstant, toInstant, agg, every, parseTags(tagFilters));
        return toJson(result);
    }

    @Tool(description = "查询指定应用可用的指标清单(指标名/类型/标签集合),用于确认某个指标是否存在")
    public String listAvailable(
            @ToolParam(description = "监控应用 appid") Long appid) {
        log.info("[kxj: AI工具 指标available - appid={}]", appid);
        return toJson(Map.of("count", metricQueryService.listAvailable(appid).size(),
                "metrics", metricQueryService.listAvailable(appid)));
    }

    private Map<String, String> parseTags(String tagFilters) {
        if (tagFilters == null || tagFilters.isBlank()) return Map.of();
        java.util.LinkedHashMap<String, String> tags = new java.util.LinkedHashMap<>();
        for (String pair : tagFilters.split(",")) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length == 2 && !kv[0].isBlank()) tags.put(kv[0].trim(), kv[1].trim());
        }
        return tags;
    }

    private Instant parseInstant(String s, Instant fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[kxj: AI工具 JSON序列化失败 - error={}]", e.getMessage());
            return "{}";
        }
    }
}