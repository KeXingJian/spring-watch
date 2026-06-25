package com.springwatch.web;

import com.springwatch.model.dto.ApiResponse;
import com.springwatch.service.MetricQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricController {

    private final MetricQueryService metricQueryService;

    /**
     * 查询指定应用当前可用的指标清单。
     *
     * GET /api/metrics/available
     *
     * @param appid 应用ID（必填）
     * @return 指标描述符列表，包含指标名、类型、标签集合等元信息
     */
    @GetMapping("/available")
    public ApiResponse<List<MetricQueryService.MetricDescriptor>> available(@RequestParam("appid") Long appid) {
        log.info("[spring-watch: 指标available - appid={}]", appid);
        return ApiResponse.ok(metricQueryService.listAvailable(appid));
    }

    /**
     * 查询指定指标的最新一个数据点。
     *
     * GET /api/metrics/latest
     *
     * @param appid     应用ID（必填）
     * @param metric    指标名（必填）
     * @param allParams 除保留参数外的其他键值对会作为标签过滤条件，例如 region=us、env=prod
     * @return 最新数据点及对应时间戳、标签等信息
     */
    @GetMapping("/latest")
    public ApiResponse<Map<String, Object>> latest(@RequestParam("appid") Long appid,
                                                   @RequestParam("metric") String metric,
                                                   @RequestParam(required = false) Map<String, String> allParams) {
        Map<String, String> tagFilters = extractTagFilters(allParams, "appid", "metric");
        log.trace("[spring-watch: 指标latest - appid={}, metric={}, allParams={}, extractedTags={}]",
                appid, metric, allParams, tagFilters);
        return ApiResponse.ok(metricQueryService.queryLatest(appid, metric, tagFilters));
    }

    /**
     * 查询指定指标在时间区间内的时序数据。
     *
     * GET /api/metrics/series
     *
     * @param appid     应用ID（必填）
     * @param metric    指标名（必填）
     * @param from      起始时间（ISO-8601，可选，默认 15 分钟前）
     * @param to        结束时间（ISO-8601，可选，默认当前时间）
     * @param agg       聚合方式，例如 mean、max、min、sum（默认 mean）
     * @param every     采样步长，例如 30s、1m（默认 30s）
     * @param allParams 除保留参数外的其他键值对会作为标签过滤条件
     * @return 时间序列数据点列表及标签维度信息
     */
    @GetMapping("/series")
    public ApiResponse<Map<String, Object>> series(@RequestParam("appid") Long appid,
                                                   @RequestParam("metric") String metric,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                   @RequestParam(required = false, defaultValue = "mean") String agg,
                                                   @RequestParam(required = false, defaultValue = "30s") String every,
                                                   @RequestParam(required = false) Map<String, String> allParams) {
        Map<String, String> tagFilters = extractTagFilters(allParams, "appid", "metric", "from", "to", "agg", "every");
        Instant fromInstant = (from == null) ? Instant.now().minusSeconds(900) : from;
        Instant toInstant = (to == null) ? Instant.now() : to;
        log.info("[spring-watch: 指标series - appid={}, metric={}, from={}, to={}, agg={}, every={}]",
                appid, metric, fromInstant, toInstant, agg, every);
        return ApiResponse.ok(metricQueryService.querySeries(appid, metric, fromInstant, toInstant, agg, every, tagFilters));
    }

    /**
     * 按指定标签维度对指标进行分组聚合查询。
     *
     * GET /api/metrics/grouped
     *
     * @param appid   应用ID（必填）
     * @param metric  指标名（必填）
     * @param groupBy 分组标签名（必填），单标签如 instance、method、status;
     *                也支持逗号分隔的多维组合，例如 device,direction
     * @return 按 groupBy 维度分组的聚合结果，单条包含 group(组合 key)、
     *         tags(各维度键值) 与 value
     */
    @GetMapping("/grouped")
    public ApiResponse<Map<String, Object>> grouped(@RequestParam("appid") Long appid,
                                                    @RequestParam("metric") String metric,
                                                    @RequestParam("groupBy") String groupBy,
                                                    @RequestParam(required = false, defaultValue = "last") String agg) {
        log.info("[spring-watch: 指标grouped - appid={}, metric={}, groupBy={}, agg={}]", appid, metric, groupBy, agg);
        return ApiResponse.ok(metricQueryService.queryGrouped(appid, metric, groupBy, agg));
    }

    /**
     * 对 histogram 类型指标按分位数估算查询，常用于延迟类指标的 P50/P95/P99。
     *
     * GET /api/metrics/histogram-quantile
     *
     * @param appid     应用ID（必填）
     * @param metric    指标名（必填），需为 histogram 类型
     * @param quantiles 分位数列表，逗号分隔，范围 [0,1]（默认 0.5,0.95,0.99）
     * @param from      起始时间（ISO-8601，可选，默认 15 分钟前）
     * @param to        结束时间（ISO-8601，可选，默认当前时间）
     * @param every     采样步长，例如 30s、1m（默认 30s）
     * @return 各分位数对应的估算值序列
     */
    @GetMapping("/histogram-quantile")
    public ApiResponse<Map<String, Object>> histogramQuantile(@RequestParam("appid") Long appid,
                                                              @RequestParam("metric") String metric,
                                                              @RequestParam(required = false, defaultValue = "0.5,0.95,0.99") String quantiles,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                              @RequestParam(required = false, defaultValue = "30s") String every,
                                                              @RequestParam(required = false) Map<String, String> allParams) {
        List<Double> qs = new ArrayList<>();
        for (String s : quantiles.split(",")) {
            try {
                qs.add(Double.parseDouble(s.trim()));
            } catch (Exception ignore) {
            }
        }
        if (qs.isEmpty()) qs.addAll(Arrays.asList(0.5, 0.95, 0.99));
        Map<String, String> tagFilters = extractTagFilters(allParams, "appid", "metric", "quantiles", "from", "to", "every");
        Instant fromInstant = (from == null) ? Instant.now().minusSeconds(900) : from;
        Instant toInstant = (to == null) ? Instant.now() : to;
        log.info("[spring-watch: 指标histogram-quantile - appid={}, metric={}, quantiles={}, from={}, to={}, tags={}]",
                appid, metric, qs, fromInstant, toInstant, tagFilters);
        return ApiResponse.ok(metricQueryService.queryHistogramQuantile(appid, metric, qs, fromInstant, toInstant, every, tagFilters));
    }

    /**
     * 按指标名前缀模糊查询匹配的指标列表。
     *
     * GET /api/metrics/by-prefix
     *
     * @param appid  应用ID（必填）
     * @param prefix 指标名前缀（必填），例如 http_、jvm_
     * @return 匹配到的指标描述符列表及总数
     */
    @GetMapping("/by-prefix")
    public ApiResponse<Map<String, Object>> byPrefix(@RequestParam("appid") Long appid,
                                                     @RequestParam("prefix") String prefix) {
        log.info("[spring-watch: 指标by-prefix - appid={}, prefix={}]", appid, prefix);
        List<MetricQueryService.MetricDescriptor> all = metricQueryService.listAvailable(appid);
        List<MetricQueryService.MetricDescriptor> matched = new ArrayList<>();
        for (MetricQueryService.MetricDescriptor d : all) {
            if (d.getMetric() != null && d.getMetric().startsWith(prefix)) {
                matched.add(d);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", matched.size());
        out.put("rows", matched);
        return ApiResponse.ok(out);
    }

    /**
     * 从请求参数全集中剔除保留字段，提取出真正的标签过滤条件。
     *
     * @param allParams 请求参数全集
     * @param reserved  不作为标签的保留参数名，如 appid、metric、from、to 等
     * @return 标签名 -> 标签值的过滤条件映射
     */
    private static Map<String, String> extractTagFilters(Map<String, String> allParams, String... reserved) {
        if (allParams == null) return Map.of();
        java.util.Set<String> reservedSet = java.util.Set.of(reserved);
        Map<String, String> tags = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : allParams.entrySet()) {
            if (reservedSet.contains(e.getKey())) continue;
            if (e.getValue() == null) continue;
            tags.put(e.getKey(), e.getValue());
        }
        return tags;
    }
}
