package com.springwatch.web;

import com.springwatch.analysis.LogAggregator;
import com.springwatch.analysis.LogAnomalyDetector;
import com.springwatch.model.dto.ApiResponse;
import com.springwatch.service.LogQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogQueryService logQueryService;
    private final LogAggregator logAggregator;
    private final LogAnomalyDetector anomalyDetector;

    /**
     * 关键字检索 + 多维过滤
     */
    @GetMapping("/search")
    public ApiResponse<List<LogQueryService.LogRow>> search(
            @RequestParam("appid") Long appid,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String logger,
            @RequestParam(required = false) String threadName,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String fingerprint,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "10") int limit) {
        Instant fromInstant = (from == null) ? Instant.now().minusSeconds(900) : from;
        Instant toInstant = (to == null) ? Instant.now() : to;
        log.info("[spring-watch: log search - appid={}, keyword={}, level={}, from={}, to={}]",
                appid, keyword, level, fromInstant, toInstant);
        return ApiResponse.ok(logQueryService.search(appid, keyword, level, logger, threadName,
                traceId, fingerprint, fromInstant, toInstant, limit));
    }

    /**
     * 级别分布(总览 + 饼图)
     */
    @GetMapping("/levels")
    public ApiResponse<List<LogQueryService.LevelCount>> levels(
            @RequestParam("appid") Long appid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant fromInstant = (from == null) ? Instant.now().minusSeconds(3600) : from;
        Instant toInstant = (to == null) ? Instant.now() : to;
        return ApiResponse.ok(logQueryService.levelDistribution(appid, fromInstant, toInstant));
    }

    /**
     * 错误率时序(total / error / warn)
     */
    @GetMapping("/stats/error-rate-series")
    public ApiResponse<List<LogQueryService.ErrorRateBucket>> errorRateSeries(
            @RequestParam("appid") Long appid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1m") String every) {
        Instant fromInstant = (from == null) ? Instant.now().minusSeconds(3600) : from;
        Instant toInstant = (to == null) ? Instant.now() : to;
        return ApiResponse.ok(logQueryService.errorRateSeries(appid, fromInstant, toInstant, every));
    }

    /**
     * 当前窗口错误率快照
     */
    @GetMapping("/stats/error-rate")
    public ApiResponse<LogAggregator.ErrorRateStats> errorRate(
            @RequestParam("appid") Long appid,
            @RequestParam(defaultValue = "60") int windowSeconds) {
        Instant now = Instant.now();
        return ApiResponse.ok(logAggregator.errorRate(appid, now.minusSeconds(Math.max(windowSeconds, 10)), now));
    }

    /**
     * TopN 异常模式(按 fingerprint 聚合)
     */
    @GetMapping("/patterns")
    public ApiResponse<List<LogQueryService.PatternTop>> patterns(
            @RequestParam("appid") Long appid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "10") int topN) {
        Instant fromInstant = (from == null) ? Instant.now().minusSeconds(3600) : from;
        Instant toInstant = (to == null) ? Instant.now() : to;
        return ApiResponse.ok(logQueryService.topFingerprints(appid, fromInstant, toInstant, topN, level));
    }

    /**
     * 单个 fingerprint 详情(模式名 + 最近样本)
     */
    @GetMapping("/fingerprint/{fp}")
    public ApiResponse<LogQueryService.FingerprintDetail> fingerprintDetail(
            @RequestParam("appid") Long appid,
            @PathVariable("fp") String fp,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant fromInstant = (from == null) ? Instant.now().minusSeconds(86400) : from;
        Instant toInstant = (to == null) ? Instant.now() : to;
        return ApiResponse.ok(logQueryService.fingerprintDetail(appid, fp, fromInstant, toInstant));
    }

    /**
     * 异常突增检测
     */
    @GetMapping("/anomaly")
    public ApiResponse<Map<String, Object>> anomaly(@RequestParam("appid") Long appid,
                                                     @RequestParam(defaultValue = "300") int windowSeconds,
                                                     @RequestParam(defaultValue = "3.0") double multiplier) {
        Instant now = Instant.now();
        var stats = logAggregator.errorRate(appid, now.minusSeconds(windowSeconds), now);
        var result = anomalyDetector.isErrorRateSpiking(appid, stats.errorRate(), multiplier);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowSeconds", windowSeconds);
        out.put("multiplier", multiplier);
        out.put("spiking", result.spiking());
        out.put("lastRate", result.lastRate());
        out.put("stats", stats);
        return ApiResponse.ok(out);
    }

    /**
     * 按 traceId 反查
     */
    @GetMapping("/trace/{traceId}")
    public ApiResponse<List<LogQueryService.LogRow>> byTrace(
            @PathVariable("traceId") String traceId,
            @RequestParam(value = "appid", required = false) Long appid) {
        return ApiResponse.ok(logQueryService.byTraceId(traceId, appid));
    }

    /**
     * 上下文(选中行 ±N 秒)
     */
    @GetMapping("/context")
    public ApiResponse<List<LogQueryService.LogRow>> context(
            @RequestParam("appid") Long appid,
            @RequestParam("time") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant time,
            @RequestParam(required = false) String threadName,
            @RequestParam(required = false) String logger,
            @RequestParam(required = false) String host,
            @RequestParam(defaultValue = "30") int beforeSec,
            @RequestParam(defaultValue = "30") int afterSec,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(logQueryService.context(appid, time, threadName, logger, host, beforeSec, afterSec, limit));
    }

    /**
     * 高频去重模式
     */
    @GetMapping("/dedup/top")
    public ApiResponse<List<LogQueryService.DedupTop>> dedupTop(
            @RequestParam("appid") Long appid,
            @RequestParam(defaultValue = "10") int topN) {
        return ApiResponse.ok(logQueryService.topDedup(appid, topN));
    }
}
