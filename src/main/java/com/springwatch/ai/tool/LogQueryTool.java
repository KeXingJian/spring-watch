package com.springwatch.ai.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;
import com.springwatch.service.LogQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogQueryTool {

    private final LogQueryService logQueryService;
    private final ObjectMapper objectMapper;

    @Tool(description = "按关键字检索日志,可按级别过滤(ERROR/WARN/INFO),返回最近日志行(含时间/级别/logger/指纹/消息)。注意:日志可能含业务敏感数据,回答时需脱敏摘要")
    public String search(
            @ToolParam(description = "监控应用 appid") Long appid,
            @ToolParam(description = "关键字,可为空") String keyword,
            @ToolParam(description = "级别过滤 ERROR/WARN/INFO,可为空") String level,
            @ToolParam(description = "起始时间 ISO-8601,可为空(默认15分钟前)") String from,
            @ToolParam(description = "结束时间 ISO-8601,可为空(默认现在)") String to,
            @ToolParam(description = "返回条数,默认20,最大100") Integer limit) {
        Instant fromInstant = parseInstant(from, Instant.now().minusSeconds(900));
        Instant toInstant = parseInstant(to, Instant.now());
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        log.info("[kxj: AI工具 日志检索 - appid={}, keyword={}, level={}, from={}, to={}]",
                appid, keyword, level, fromInstant, toInstant);
        LogQueryService.SearchResult result = logQueryService.search(
                appid, keyword, level, null, null, null, null, fromInstant, toInstant, 1, size);
        return toJson(new SearchOut(result.total(), result.rows(), result.error()));
    }

    @Tool(description = "统计时间区间内日志指纹 TopN(相似日志聚类后的模式),用于快速定位高频错误模式。返回指纹/模式/条数/去重数")
    public String topFingerprints(
            @ToolParam(description = "监控应用 appid") Long appid,
            @ToolParam(description = "起始时间 ISO-8601,可为空(默认1小时前)") String from,
            @ToolParam(description = "结束时间 ISO-8601,可为空(默认现在)") String to,
            @ToolParam(description = "返回TopN,默认10,最大50") Integer topN,
            @ToolParam(description = "级别过滤 ERROR/WARN/INFO,可为空") String level) {
        Instant fromInstant = parseInstant(from, Instant.now().minusSeconds(3600));
        Instant toInstant = parseInstant(to, Instant.now());
        int n = topN == null || topN <= 0 ? 10 : Math.min(topN, 50);
        log.info("[kxj: AI工具 日志指纹TopN - appid={}, from={}, to={}, topN={}, level={}]",
                appid, fromInstant, toInstant, n, level);
        return toJson(logQueryService.topFingerprints(appid, fromInstant, toInstant, n, level));
    }

    @Tool(description = "查询时间区间内日志错误率时序(按窗口统计 total/error/warn),用于判断错误率突增")
    public String errorRateSeries(
            @ToolParam(description = "监控应用 appid") Long appid,
            @ToolParam(description = "起始时间 ISO-8601,可为空(默认1小时前)") String from,
            @ToolParam(description = "结束时间 ISO-8601,可为空(默认现在)") String to,
            @ToolParam(description = "统计窗口,如 1m/5m,默认 1m") String every) {
        Instant fromInstant = parseInstant(from, Instant.now().minusSeconds(3600));
        Instant toInstant = parseInstant(to, Instant.now());
        log.info("[kxj: AI工具 错误率时序 - appid={}, from={}, to={}, every={}]",
                appid, fromInstant, toInstant, every);
        return toJson(logQueryService.errorRateSeries(appid, fromInstant, toInstant, every));
    }

    @Tool(description = "查询某个日志指纹的详情(模式/示例消息/异常堆栈摘要),用于根因分析")
    public String fingerprintDetail(
            @ToolParam(description = "监控应用 appid") Long appid,
            @ToolParam(description = "日志指纹 sha1Hex") String fingerprint,
            @ToolParam(description = "起始时间 ISO-8601,可为空(默认1小时前)") String from,
            @ToolParam(description = "结束时间 ISO-8601,可为空(默认现在)") String to) {
        Instant fromInstant = parseInstant(from, Instant.now().minusSeconds(3600));
        Instant toInstant = parseInstant(to, Instant.now());
        log.info("[kxj: AI工具 指纹详情 - appid={}, fingerprint={}]", appid, fingerprint);
        return toJson(logQueryService.fingerprintDetail(appid, fingerprint, fromInstant, toInstant));
    }

    private Instant parseInstant(String s, Instant fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchOut(long total, List<LogQueryService.LogRow> rows, String error) {
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