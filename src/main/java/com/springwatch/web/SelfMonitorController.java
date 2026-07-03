package com.springwatch.web;

import com.springwatch.collector.AppPullTask;
import com.springwatch.monitor.SelfMonitorCollector;
import com.springwatch.service.SelfMetricQueryService;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/api/self")
@RequiredArgsConstructor
public class SelfMonitorController {

    private final SelfMonitorCollector collector;
    private final SelfMetricQueryService selfMetricQueryService;
    private final AppPullTask appPullTask;

    /**
     * 内存 ring 里的最新一帧采样 - 5s 轮询的快速缓存，
     * 与 24h 历史查询解耦，避免每个轮询周期都打 InfluxDB。
     */
    @GetMapping("/realtime")
    public Map<String, Object> realtime() {
        SelfMonitorCollector.Sample s = collector.latest();
        if (s == null) {
            return Map.of("ready", false, "size", collector.size());
        }
        return Map.of("ready", true, "size", collector.size(), "sample", s);
    }

    /**
     * 内存 ring 里的最近 N 帧（≤60 = 10min），仅供调试/对比使用。
     * 前端 1h/6h/24h 视图统一走 /series 查 InfluxDB,不再用本端点拉历史曲线。
     */
    @GetMapping("/timeseries")
    public Map<String, Object> timeseries(@RequestParam(defaultValue = "60") int window) {
        List<SelfMonitorCollector.Sample> samples = collector.window(window);
        return Map.of("size", samples.size(), "window", window, "samples", samples);
    }

    /**
     * 近 24h 出现过的 category 列表，前端用作 24h 视图的导航。
     */
    @GetMapping("/categories")
    public Map<String, Object> categories() {
        List<String> cats = selfMetricQueryService.listCategories();
        return Map.of("categories", cats, "count", cats.size());
    }

    /**
     * 列出某 category 下的所有 metric 名称（近 24h 去重）。
     *
     * @param category  jvm / process / meter
     * @param meterType 可选，过滤 meter category 下的 counter/gauge/timer
     * @param gcName    可选，过滤 jvm.gc.* 中具体 GC 名
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics(@RequestParam(required = false) String category,
                                       @RequestParam(required = false) String meterType,
                                       @RequestParam(required = false) String gcName) {
        Map<String, String> tagFilters = new LinkedHashMap<>();
        if (meterType != null && !meterType.isBlank()) tagFilters.put("meter_type", meterType);
        if (gcName != null && !gcName.isBlank()) tagFilters.put("gc_name", gcName);
        List<SelfMetricQueryService.SelfMetricDescriptor> metrics =
                selfMetricQueryService.listMetrics(category, tagFilters);
        return Map.of("metrics", metrics, "count", metrics.size());
    }

    /**
     * 时序查询 - 24h 展示核心接口，与应用指标 /api/metrics/series 走同款 Flux 模板。
     * 默认按 range 自适应 step（1h→10s, 6h→30s, 24h→1m）防止大区间把前后端打爆。
     */
    @GetMapping("/series")
    public Map<String, Object> series(@RequestParam(required = false) String category,
                                       @RequestParam("metric") String metric,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                       @RequestParam(required = false, defaultValue = "mean") String agg,
                                       @RequestParam(required = false) String every,
                                       @RequestParam(required = false) String meterType,
                                       @RequestParam(required = false) String gcName,
                                       @RequestParam(required = false, defaultValue = "value") String field) {
        Instant fromInstant = (from == null) ? Instant.now().minusSeconds(3600) : from;
        Instant toInstant = (to == null) ? Instant.now() : to;
        Map<String, String> tagFilters = new LinkedHashMap<>();
        if (meterType != null && !meterType.isBlank()) tagFilters.put("meter_type", meterType);
        if (gcName != null && !gcName.isBlank()) tagFilters.put("gc_name", gcName);
        String everyResolved = (every == null || every.isBlank())
                ? SelfMetricQueryService.defaultEveryForFrontend(fromInstant, toInstant)
                : every;
        return selfMetricQueryService.querySeries(category, metric, fromInstant, toInstant, agg, everyResolved, tagFilters, field);
    }

    /**
     * 取某指标最新一帧（带完整 tags），用于仪表盘卡片。
     */
    @GetMapping("/latest")
    public Map<String, Object> latest(@RequestParam("metric") String metric,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false) String meterType,
                                      @RequestParam(required = false) String gcName) {
        Map<String, String> tagFilters = new LinkedHashMap<>();
        if (meterType != null && !meterType.isBlank()) tagFilters.put("meter_type", meterType);
        if (gcName != null && !gcName.isBlank()) tagFilters.put("gc_name", gcName);
        return selfMetricQueryService.queryLatest(category, metric, tagFilters);
    }

    /**
     * 一次拉取一个自监控 view 的全部时序(后端并发查 InfluxDB),替代 N 次 /series 调用。
     * view: overview / collect / jvm / process。
     * 响应: { view, from, to, every, specs: { key: { series: [...], count, error? } }, errors: [...], elapsedMs }
     */
    @GetMapping("/view/{view}")
    public Map<String, Object> view(@PathVariable String view,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                    @RequestParam(required = false) String every) {
        Instant fromInstant = (from == null) ? Instant.now().minusSeconds(3600) : from;
        Instant toInstant = (to == null) ? Instant.now() : to;
        return selfMetricQueryService.queryView(view, fromInstant, toInstant, every);
    }

    /**
     * 采集耗时直方图当前快照(12 桶: 1s-10s + >10s + 不可达)。
     * 给自监控"采集"tab 一次性拉取,前端画柱图,不走 InfluxDB。
     */
    @GetMapping("/pull/histogram")
    public Map<String, Object> pullHistogram() {
        return appPullTask.snapshotPullHistogram();
    }
}
