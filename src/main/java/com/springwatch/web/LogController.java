package com.springwatch.web;

import com.springwatch.analysis.LogAggregator;
import com.springwatch.analysis.LogAnomalyDetector;
import com.springwatch.analysis.LogMetricsLinker;
import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.entity.LogDedupCount;
import com.springwatch.repository.LogDedupCountRepository;
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
    private final LogMetricsLinker metricsLinker;
    private final LogDedupCountRepository dedupCountRepository;

    /**
     * kxj: 关键字检索-message/throwable全文匹配
     */
    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(
            @RequestParam Long appid,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String level,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") int limit) {
        log.info("[kxj: LogController search - appid={}, keyword={}, level={}, limit={}]",
                appid, keyword, level, limit);
        return ApiResponse.ok(logQueryService.search(appid, keyword, level, from, to, limit));
    }

    /**
     * kxj: 错误率-窗口聚合返回total/error/warn/rate
     */
    @GetMapping("/stats/error-rate")
    public ApiResponse<LogAggregator.ErrorRateStats> errorRate(
            @RequestParam Long appid,
            @RequestParam(defaultValue = "60") int windowSeconds) {
        Instant now = Instant.now();
        return ApiResponse.ok(logAggregator.errorRate(appid, now.minusSeconds(windowSeconds), now));
    }

    /**
     * kxj: 错误率时序-趋势曲线
     */
    @GetMapping("/stats/error-rate-series")
    public ApiResponse<List<LogAggregator.ErrorRatePoint>> errorRateSeries(
            @RequestParam Long appid,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1m") String every) {
        return ApiResponse.ok(logAggregator.errorRateSeries(appid, from, to, every));
    }

    /**
     * kxj: TopN异常模式聚类
     */
    @GetMapping("/patterns")
    public ApiResponse<List<LogAggregator.PatternStats>> topPatterns(
            @RequestParam Long appid,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "10") int topN) {
        return ApiResponse.ok(logAggregator.topPatterns(appid, from, to, topN));
    }

    /**
     * kxj: 突增异常报告
     */
    @GetMapping("/anomaly")
    public ApiResponse<AnomalyReport> anomaly(
            @RequestParam Long appid,
            @RequestParam(defaultValue = "300") int windowSeconds,
            @RequestParam(defaultValue = "3.0") double multiplier) {
        Instant now = Instant.now();
        LogAggregator.ErrorRateStats stats = logAggregator.errorRate(appid, now.minusSeconds(windowSeconds), now);
        boolean spiking = anomalyDetector.isErrorRateSpiking(appid, stats.errorRate(), multiplier);
        return ApiResponse.ok(new AnomalyReport(appid, stats, spiking, multiplier));
    }

    /**
     * kxj: Trace串联-按traceId回查日志
     */
    @GetMapping("/trace/{traceId}")
    public ApiResponse<List<Map<String, Object>>> byTrace(
            @PathVariable String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "200") int limit) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minusSeconds(3600);
        return ApiResponse.ok(logQueryService.findByTraceId(traceId, start, end, limit));
    }

    /**
     * kxj: P0 无上下文查询 - 给定一条日志的 anchor 时间戳,按 thread/host/logger
     * 任一维度拉取 ±N 秒相邻日志,用于排障"在 Kibana 里看上下文"的场景
     * 至少传一个 threadName/host/logger,否则拒绝全表扫
     */
    @GetMapping("/context")
    public ApiResponse<List<Map<String, Object>>> context(
            @RequestParam Long appid,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant anchor,
            @RequestParam(required = false) String threadName,
            @RequestParam(required = false) String host,
            @RequestParam(required = false) String logger,
            @RequestParam(defaultValue = "30") int before,
            @RequestParam(defaultValue = "30") int after,
            @RequestParam(defaultValue = "100") int limit) {
        log.info("[kxj: LogController context - appid={}, anchor={}, thread={}, host={}, logger={}, ±{}s/{}s]",
                appid, anchor, threadName, host, logger, before, after);
        return ApiResponse.ok(logQueryService.getContext(
                appid, anchor, threadName, host, logger, before, after, limit));
    }

    /**
     * kxj: 同模式样本-按fingerprint回查
     */
    @GetMapping("/fingerprint/{fingerprint}")
    public ApiResponse<List<Map<String, Object>>> byFingerprint(
            @PathVariable String fingerprint,
            @RequestParam(required = false) Long appid,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(logQueryService.findByFingerprint(appid, fingerprint, from, to, limit));
    }

    /**
     * kxj: 指标-日志关联-错误率+指标均值的综合得分
     */
    @GetMapping("/correlate")
    public ApiResponse<LogMetricsLinker.CorrelationReport> correlate(
            @RequestParam Long appid,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String metric) {
        return ApiResponse.ok(metricsLinker.correlate(appid, from, to, metric));
    }

    public record AnomalyReport(Long appid, LogAggregator.ErrorRateStats stats,
                                 boolean spiking, double multiplier) {
    }

    /**
     * kxj: P0 dedup 双写落地 - 从持久化的 log_dedup_count 表查某 appid 的 dedup 计数 Top N
     * 用于看板上"哪些模式被频繁去重(噪声)"展示
     */
    @GetMapping("/dedup/top")
    public ApiResponse<List<LogDedupCount>> topDedup(
            @RequestParam Long appid,
            @RequestParam(defaultValue = "20") int limit) {
        int safe = limit > 0 && limit <= 200 ? limit : 20;
        log.debug("[kxj: LogController topDedup - appid={}, limit={}]", appid, safe);
        return ApiResponse.ok(dedupCountRepository.findByAppidOrderByDedupCountDesc(appid)
                .stream().limit(safe).toList());
    }
}
