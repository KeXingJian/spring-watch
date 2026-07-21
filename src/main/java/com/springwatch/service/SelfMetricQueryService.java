package com.springwatch.service;

import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SelfMetricQueryService {

    private static final String MEASUREMENT = "self_monitor_metrics";
    private static final String APPID_SELF = "self";

    private static final java.util.Set<String> EXCLUDED_TAG_KEYS = java.util.Set.of(
            "appid", "category", "metric", "meter_type",
            "_field", "_measurement", "_start", "_stop", "_value", "_time", "result", "table", "host"
    );

    private final QueryApi queryApi;
    private final MeterRegistry meterRegistry;

    @Value("${influxdb.self-metrics-bucket:self_metrics}")
    private String bucket;

    @Value("${influxdb.org}")
    private String influxOrg;

    private Counter seriesFailCounter;
    private Counter listFailCounter;
    private Counter latestFailCounter;
    private Timer seriesTimer;
    private Timer listTimer;
    private Timer latestTimer;

    @PostConstruct
    void init() {
        this.seriesFailCounter = Counter.builder("spring.watch.self.metric.query.series.fail")
                .description("自监控时序查询失败次数").register(meterRegistry);
        this.listFailCounter = Counter.builder("spring.watch.self.metric.query.list.fail")
                .description("自监控维度/指标列表查询失败次数").register(meterRegistry);
        this.latestFailCounter = Counter.builder("spring.watch.self.metric.query.latest.fail")
                .description("自监控最新值查询失败次数").register(meterRegistry);
        this.seriesTimer = Timer.builder("spring.watch.self.metric.query.series")
                .description("自监控时序查询耗时").register(meterRegistry);
        this.listTimer = Timer.builder("spring.watch.self.metric.query.list")
                .description("自监控维度/指标列表查询耗时").register(meterRegistry);
        this.latestTimer = Timer.builder("spring.watch.self.metric.query.latest")
                .description("自监控最新值查询耗时").register(meterRegistry);
    }

    /**
     * 列出近 24h 出现过的 category。前端用作顶部导航。
     */
    public List<String> listCategories() {
        long startNs = System.nanoTime();
        try {
            String flux = String.format(
                    """
                            from(bucket: "%s")
                              |> range(start: -24h)
                              |> filter(fn: (r) => r._measurement == "%s" and r.appid == "%s")
                              |> group(columns: ["category"])
                              |> distinct(column: "category")
                              |> keep(columns: ["category"])""",
                    bucket, MEASUREMENT, APPID_SELF);
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            List<String> out = new ArrayList<>();
            for (FluxTable t : tables) {
                for (FluxRecord r : t.getRecords()) {
                    Object v = r.getValueByKey("category");
                    if (v != null) out.add(v.toString());
                }
            }
            return out;
        } catch (Exception e) {
            listFailCounter.increment();
            log.warn("[kxj: 自监控 category 查询失败 - error={}]", e.getMessage());
            return List.of();
        } finally {
            listTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 列出某 category 下的所有 metric 名称。近 24h 去重，附 latestValue/lastTime。
     *
     * @param category    jvm / process / meter；为空时不过滤
     * @param extraTags   额外标签过滤，例如 meter_type=counter / gc_name=G1 Young Generation
     */
    public List<SelfMetricDescriptor> listMetrics(String category, Map<String, String> extraTags) {
        long startNs = System.nanoTime();
        try {
            StringBuilder flux = new StringBuilder(String.format(
                    """
                            from(bucket: "%s")
                              |> range(start: -24h)
                              |> filter(fn: (r) => r._measurement == "%s"
                                                and r.appid == "%s"
                                                and r._field == "value\"""",
                    bucket, MEASUREMENT, APPID_SELF));
            if (category != null && !category.isBlank()) {
                flux.append("\n                    and r.category == \"").append(escape(category)).append("\"");
            }
            if (extraTags != null) {
                for (Map.Entry<String, String> e : extraTags.entrySet()) {
                    if (e.getValue() == null) continue;
                    flux.append("\n                    and r.").append(e.getKey())
                            .append(" == \"").append(escape(e.getValue())).append("\"");
                }
            }
            flux.append(")\n  |> group(columns: [\"metric\"])\n  |> last()");

            List<FluxTable> tables = queryApi.query(flux.toString(), influxOrg);
            List<SelfMetricDescriptor> out = new ArrayList<>();
            for (FluxTable t : tables) {
                for (FluxRecord r : t.getRecords()) {
                    String metric = (String) r.getValueByKey("metric");
                    if (metric == null) continue;
                    SelfMetricDescriptor d = new SelfMetricDescriptor();
                    d.setMetric(metric);
                    d.setCategory((String) r.getValueByKey("category"));
                    Object mt = r.getValueByKey("meter_type");
                    if (mt != null) d.setMeterType(mt.toString());
                    Object gn = r.getValueByKey("gc_name");
                    if (gn != null) d.setGcName(gn.toString());
                    Object v = r.getValue();
                    if (v instanceof Number n) d.setLastValue(n.doubleValue());
                    Object time = r.getValueByKey("_time");
                    if (time instanceof Instant ins) d.setLastTime(ins);
                    out.add(d);
                }
            }
            return out;
        } catch (Exception e) {
            listFailCounter.increment();
            log.warn("[kxj: 自监控 metric 列表查询失败 - category={}, tags={}, error={}]",
                    category, extraTags, e.getMessage());
            return List.of();
        } finally {
            listTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 时序查询（与应用指标 querySeries 走同款 Flux 模板）。支持 1h/6h/24h 范围。
     *
     * <p>支持的 agg: mean / max / min / sum / last / count / rate。
     * <p>rate 等价于 derivative(unit: 1s, nonNegative: true) 后再 aggregateWindow 取 mean，
     * 适合 counter / GC time 这种单调递增字段，输出单位是"每秒增量"。
     * <p>field 决定读哪个 field 列：value（默认）/ count / total_ms / max_ms。
     * timer 类指标的 count / total_ms 速率用于近似"忙碌度"。
     */
    public Map<String, Object> querySeries(String category, String metric, Instant from, Instant to,
                                           String agg, String every, Map<String, String> tagFilters,
                                           String field) {
        long startNs = System.nanoTime();
        try {
            String aggLower = agg == null ? "mean" : agg.toLowerCase();
            String aggFn = switch (aggLower) {
                case "max" -> "max";
                case "min" -> "min";
                case "sum" -> "sum";
                case "last" -> "last";
                case "count" -> "count";
                default -> "mean";
            };
            String window = (every == null || every.isBlank()) ? defaultEvery(from, to) : every;
            String filter = buildTagFilter(category, metric, tagFilters);
            String rateStep = "rate".equals(aggLower) ? "|> derivative(unit: 1s, nonNegative: true)\n" : "";
            String targetField = (field == null || field.isBlank()) ? "value" : field;
            String flux = String.format(
                    """
                            from(bucket: "%s")
                              |> range(start: %s, stop: %s)
                              |> filter(fn: (r) => r._measurement == "%s"
                                                and r.appid == "%s"
                                                and r._field == "%s"%s)
                            %s\
                              |> aggregateWindow(every: %s, fn: %s, createEmpty: false)
                              |> yield(name: "series")""",
                    bucket, formatInstant(from), formatInstant(to), MEASUREMENT, APPID_SELF,
                    escape(targetField), filter, rateStep, window, aggFn);
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            return parseSeries(tables, metric);
        } catch (Exception e) {
            seriesFailCounter.increment();
            log.warn("[kxj: 自监控 series 查询失败 - metric={}, error={}]", metric, e.getMessage());
            return Map.of("series", List.of(), "count", 0, "error", e.getMessage());
        } finally {
            seriesTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 取某指标最新一帧（含完整标签与时间戳），用于卡片实时值。
     *
     * <p>agg:
     * <ul>
     *   <li>last (默认):  -1h 范围内每条 series 的最后一个 value,适合 gauge / summary(取最新快照)</li>
     *   <li>rate: -5m 范围 derivative(unit: 1s, nonNegative: true) + aggregateWindow(mean) 取最后 1 帧,
     *       适合 counter(累计计数 → 每秒增量)。聚合步长 window 默认 10s,与自监控 10s 采样周期对齐。</li>
     * </ul>
     */

    public Map<String, Object> queryLatest(String category, String metric, Map<String, String> tagFilters,
                                           String agg, String window) {
        long startNs = System.nanoTime();
        try {
            String filter = buildTagFilter(category, metric, tagFilters);
            String aggLower = agg == null ? "last" : agg.toLowerCase();
            String every = (window == null || window.isBlank()) ? "10s" : window;
            String flux;
            if ("rate".equals(aggLower)) {
                flux = String.format(
                        """
                                from(bucket: "%s")
                                  |> range(start: -5m)
                                  |> filter(fn: (r) => r._measurement == "%s"
                                                    and r.appid == "%s"
                                                    and r._field == "value"%s)
                                  |> derivative(unit: 1s, nonNegative: true)
                                  |> aggregateWindow(every: %s, fn: mean, createEmpty: false)
                                  |> sort(columns: ["_time"], desc: true)
                                  |> limit(n: 50)""",
                        bucket, MEASUREMENT, APPID_SELF, filter, every);
            } else {
                flux = String.format(
                        """
                                from(bucket: "%s")
                                  |> range(start: -1h)
                                  |> filter(fn: (r) => r._measurement == "%s"
                                                    and r.appid == "%s"
                                                    and r._field == "value"%s)
                                  |> sort(columns: ["_time"], desc: true)
                                  |> limit(n: 50)""",
                        bucket, MEASUREMENT, APPID_SELF, filter);
            }
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            Map<String, Map<String, Object>> latestByTags = new LinkedHashMap<>();
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    String key = buildAllTagsKey(record);
                    if (latestByTags.containsKey(key)) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    Object v = record.getValue();
                    row.put("value", v instanceof Number n ? n.doubleValue() : null);
                    Object t = record.getValueByKey("_time");
                    row.put("time", t == null ? null : t.toString());
                    Map<String, Object> tags = new LinkedHashMap<>();
                    collectTags(record, tags);
                    row.put("tags", tags);
                    latestByTags.put(key, row);
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>(latestByTags.values());
            return Map.of("rows", rows, "count", rows.size());
        } catch (Exception e) {
            latestFailCounter.increment();
            log.warn("[kxj: 自监控 latest 查询失败 - metric={}, error={}]", metric, e.getMessage());
            return Map.of("rows", List.of(), "count", 0, "error", e.getMessage());
        } finally {
            latestTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 应用指标式 series 解析（与 MetricQueryService.querySeries 行为一致）。
     * 多个 series（按 tag 区分）都会被返回。
     */
    private static Map<String, Object> parseSeries(List<FluxTable> tables, String metric) {
        List<Map<String, Object>> resultSeries = new ArrayList<>();
        for (FluxTable table : tables) {
            String seriesName = metric;
            List<Map.Entry<String, Object>> extraTags = new ArrayList<>();
            List<Map<String, Object>> points = new ArrayList<>();
            for (FluxRecord record : table.getRecords()) {
                if (points.isEmpty()) {
                    for (Map.Entry<String, Object> e : record.getValues().entrySet()) {
                        if (EXCLUDED_TAG_KEYS.contains(e.getKey())) continue;
                        if (e.getKey().startsWith("_")) continue;
                        if (e.getValue() == null) continue;
                        extraTags.add(e);
                    }
                    StringBuilder nameBuilder = new StringBuilder(metric);
                    for (Map.Entry<String, Object> e : extraTags) {
                        nameBuilder.append("{").append(e.getKey()).append("=").append(e.getValue()).append("}");
                    }
                    seriesName = nameBuilder.toString();
                }
                Map<String, Object> row = new LinkedHashMap<>();
                Object t = record.getValueByKey("_time");
                row.put("t", t == null ? null : t.toString());
                Object v = record.getValue();
                row.put("v", v instanceof Number n ? n.doubleValue() : null);
                points.add(row);
            }
            if (!points.isEmpty()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("name", seriesName);
                s.put("points", points);
                resultSeries.add(s);
            }
        }
        return Map.of("series", resultSeries, "count", resultSeries.size());
    }

    /**
     * 不同时间范围给一个合理的默认步长：≤1h→10s, ≤6h→30s, ≤24h→1m, 更大→5m。
     * 避免 24h 范围用 30s 步长把后端/前端压垮。
     * 前端 5m/15m/1h 走 10s 步长（自监控 10s 一次采样,再细也补不出数据）。
     */
    static String defaultEvery(Instant from, Instant to) {
        if (from == null || to == null) return "30s";
        long seconds = to.getEpochSecond() - from.getEpochSecond();
        if (seconds <= 3600) return "10s";
        if (seconds <= 6 * 3600) return "30s";
        if (seconds <= 24 * 3600) return "1m";
        return "5m";
    }

    /**
     * Controller 端透传给前端 - 包级别公开便于 Web 层调用。
     */
    public static String defaultEveryForFrontend(Instant from, Instant to) {
        return defaultEvery(from, to);
    }

    private static String buildTagFilter(String category, String metric, Map<String, String> tagFilters) {
        StringBuilder sb = new StringBuilder();
        if (category != null && !category.isBlank()) {
            sb.append("\n                    and r.category == \"").append(escape(category)).append("\"");
        }
        if (metric != null && !metric.isBlank()) {
            sb.append("\n                    and r.metric == \"").append(escape(metric)).append("\"");
        }
        if (tagFilters != null) {
            for (Map.Entry<String, String> e : tagFilters.entrySet()) {
                if (e.getValue() == null) continue;
                sb.append("\n                    and r.").append(e.getKey())
                        .append(" == \"").append(escape(e.getValue())).append("\"");
            }
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    private static String formatInstant(Instant t) {
        return "time(v: " + t.toString() + ")";
    }

    private static String buildAllTagsKey(FluxRecord record) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : record.getValues().entrySet()) {
            if (EXCLUDED_TAG_KEYS.contains(e.getKey())) continue;
            if (e.getKey().startsWith("_")) continue;
            if (e.getValue() == null) continue;
            sb.append(e.getKey()).append("=").append(e.getValue()).append("|");
        }
        return sb.toString();
    }

    private static void collectTags(FluxRecord record, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : record.getValues().entrySet()) {
            if (EXCLUDED_TAG_KEYS.contains(e.getKey())) continue;
            if (e.getKey().startsWith("_")) continue;
            if (e.getValue() == null) continue;
            out.put(e.getKey(), e.getValue());
        }
    }

    /**
     * 自监控页各 view 的时序指标定义。前端进入某 tab 时一次性拉取,后端并发查 InfluxDB。
     * key 是前端约定的字段名(用于响应里定位),其他字段对应一次 querySeries 调用。
     */
    public record ViewSpec(
            String key,
            String category,
            String metric,
            String agg,
            String field,
            String meterType,
            String gcName
    ) {
        public static ViewSpec of(String key, String category, String metric, String agg) {
            return new ViewSpec(key, category, metric, agg, "value", null, null);
        }
        public static ViewSpec of(String key, String category, String metric, String agg, String field) {
            return new ViewSpec(key, category, metric, agg, field, null, null);
        }
        public static ViewSpec timer(String key, String category, String metric, String agg) {
            return new ViewSpec(key, category, metric, agg, "count", "timer", null);
        }
    }

    /** view 名 -> 该 view 一次刷新所需的全部 series spec。 */
    public static final Map<String, List<ViewSpec>> VIEW_SPECS = Map.of(
            "overview", List.of(
                    ViewSpec.timer("metric_q_latest",   "meter", "spring.watch.metric.query.latest",    "rate"),
                    ViewSpec.timer("metric_q_series",   "meter", "spring.watch.metric.query.series",    "rate"),
                    ViewSpec.timer("metric_q_grouped",  "meter", "spring.watch.metric.query.grouped",   "rate"),
                    ViewSpec.timer("metric_q_histogram","meter", "spring.watch.metric.query.histogram", "rate"),
                    ViewSpec.of   ("metric_q_fail",     "meter", "spring.watch.metric.query.fail",      "rate"),
                    ViewSpec.of   ("log_q_search",      "meter", "spring.watch.log.query.search",       "rate"),
                    ViewSpec.of   ("log_q_patterns",    "meter", "spring.watch.log.query.patterns",     "rate"),
                    ViewSpec.of   ("log_q_levels",      "meter", "spring.watch.log.query.levels",       "rate"),
                    ViewSpec.of   ("log_q_trace",       "meter", "spring.watch.log.query.trace",        "rate"),
                    ViewSpec.of   ("log_q_context",     "meter", "spring.watch.log.query.context",      "rate"),
                    ViewSpec.of   ("log_q_dedup_top",   "meter", "spring.watch.log.query.dedup_top",    "rate"),
                    ViewSpec.of   ("log_q_fail",        "meter", "spring.watch.log.query.fail",         "rate"),
                    ViewSpec.of   ("http_ok",           "meter", "spring.watch.collector.http.success",     "rate"),
                    ViewSpec.of   ("http_fail",         "meter", "spring.watch.collector.http.failure",     "rate"),
                    ViewSpec.of   ("http_timeout",      "meter", "spring.watch.collector.http.timeout",     "rate"),
                    ViewSpec.of   ("http_non2xx",       "meter", "spring.watch.collector.http.non2xx",      "rate"),
                    ViewSpec.timer("http_calls",        "meter", "spring.watch.collector.http.request",     "rate"),
                    ViewSpec.of   ("retry_enq",         "meter", "spring.watch.collector.retry.enqueued",   "rate"),
                    ViewSpec.of   ("retry_drop",        "meter", "spring.watch.collector.retry.dropped",    "rate")
            ),
            "collect", List.of(
                    ViewSpec.of   ("recv_metric",   "meter", "spring.watch.consumer.metric.received",        "rate"),
                    ViewSpec.of   ("recv_log",      "meter", "spring.watch.consumer.log.received",           "rate"),
                    ViewSpec.of   ("kept_log",      "meter", "spring.watch.consumer.log.kept",               "rate"),
                    ViewSpec.of   ("persisted_dlq", "meter", "spring.watch.consumer.dlq.persisted",          "rate"),
                    ViewSpec.of   ("deduped",       "meter", "spring.watch.consumer.log.deduped",            "rate"),
                    ViewSpec.of   ("alert_cand",    "meter", "spring.watch.consumer.log.alert_candidate",   "rate"),
                    ViewSpec.of   ("parse_fail_m",  "meter", "spring.watch.consumer.metric.parse_fail",     "rate"),
                    ViewSpec.of   ("write_fail_m",  "meter", "spring.watch.consumer.metric.write_fail",     "rate"),
                    ViewSpec.of   ("parse_fail_l",  "meter", "spring.watch.consumer.log.parse_fail",        "rate"),
                    ViewSpec.of   ("write_fail_l",  "meter", "spring.watch.consumer.log.write_fail",        "rate"),
                    ViewSpec.of   ("persist_fail_dlq","meter","spring.watch.consumer.dlq.persist_fail",     "rate"),
                    ViewSpec.of   ("http_body_rej", "meter", "spring.watch.collector.http.body.rejected",   "rate"),
                    ViewSpec.of   ("inflight_rej",  "meter", "spring.watch.inflight.producer.rejected",    "rate"),
                    ViewSpec.of   ("inflight_pending","meter","spring.watch.inflight.queue.pending",       "last"),
                    ViewSpec.timer("write_calls_m", "meter", "spring.watch.consumer.metric.write",          "rate"),
                    ViewSpec.timer("write_calls_l", "meter", "spring.watch.consumer.log.write",             "rate"),
                    ViewSpec.of   ("keep",          "meter", "spring.watch.ingest.log.dedup.keep",          "rate"),
                    ViewSpec.of   ("drop",          "meter", "spring.watch.ingest.log.dedup.drop",          "rate"),
                    ViewSpec.of   ("flush",         "meter", "spring.watch.ingest.log.dedup.flush",         "rate"),
                    ViewSpec.of   ("flush_fail",    "meter", "spring.watch.ingest.log.dedup.flush_fail",    "rate"),
                    ViewSpec.timer("pull_duration", "meter", "spring.watch.collector.pull.duration",        "rate"),
                    ViewSpec.of   ("pull_unreachable","meter","spring.watch.collector.pull.unreachable",     "rate")
            ),
            "jvm", List.of(
                    ViewSpec.of("jvm_heap",        "jvm", "heap.used",       "last"),
                    ViewSpec.of("jvm_metaspace",   "jvm", "metaspace.used",  "last"),
                    ViewSpec.of("jvm_nonheap",     "jvm", "nonHeap.used",    "last"),
                    ViewSpec.of("jvm_pool",        "jvm", "pool.used",       "last"),
                    ViewSpec.of("jvm_thr_cur",     "jvm", "threads.current", "last"),
                    ViewSpec.of("jvm_thr_daemon",  "jvm", "threads.daemon",  "last"),
                    ViewSpec.of("jvm_cls_loaded",  "jvm", "classes.loaded",  "last"),
                    ViewSpec.of("jvm_gc_time",     "jvm", "gc.time_ms",      "rate")
            ),
            "process", List.of(
                    ViewSpec.of("proc_cpu",        "process", "cpu_load",         "mean"),
                    ViewSpec.of("proc_sys_cpu",    "process", "system_cpu_load",  "mean"),
                    ViewSpec.of("proc_rss",        "process", "rss_bytes",        "last"),
                    ViewSpec.of("proc_heap",       "process", "heap_used",        "last"),
                    ViewSpec.of("proc_nonheap",    "process", "non_heap_used",    "last"),
                    ViewSpec.of("proc_virt",       "process", "virtual_bytes",    "last")
            )
    );

    /**
     * 一次拉取一个 view 的全部时序。后端并发查 InfluxDB,合并为一个响应。
     * 失败容错:单个 spec 异常不影响其它 spec,失败项 spec 留空 + errors 列表记录。
     */
    public Map<String, Object> queryView(String view, Instant from, Instant to, String every) {
        List<ViewSpec> specs = VIEW_SPECS.get(view);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("view", view);
        resp.put("from", from.toString());
        resp.put("to", to.toString());
        if (specs == null) {
            resp.put("error", "unknown view: " + view);
            resp.put("specs", Map.of());
            resp.put("errors", List.of());
            return resp;
        }
        String window = (every == null || every.isBlank()) ? defaultEvery(from, to) : every;
        resp.put("every", window);

        long startNs = System.nanoTime();
        // 并发查 InfluxDB:InfluxDB QueryApi 内部已线程安全,parallelStream 走 ForkJoinPool。
        // LinkedHashMap 保留 spec 声明顺序,前端读取稳定。
        Map<String, Object> results = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<ViewSpec> orderedSpecs = specs;
        Map<String, Map<String, Object>> parallel = orderedSpecs.parallelStream()
                .collect(Collectors.toMap(
                        ViewSpec::key,
                        spec -> {
                            Map<String, String> tagFilters = new LinkedHashMap<>();
                            if (spec.meterType() != null) tagFilters.put("meter_type", spec.meterType());
                            if (spec.gcName() != null) tagFilters.put("gc_name", spec.gcName());
                            try {
                                return querySeries(spec.category(), spec.metric(), from, to, spec.agg(), window, tagFilters, spec.field());
                            } catch (Exception e) {
                                log.warn("[kxj: view={} spec={} 失败 - {}]", view, spec.key(), e.getMessage());
                                return new LinkedHashMap<>(Map.of("series", List.of(), "count", 0, "error", e.getMessage()));
                            }
                        },
                        (a, _) -> a,
                        LinkedHashMap::new
                ));
        for (ViewSpec s : orderedSpecs) {
            Map<String, Object> r = parallel.get(s.key());
            results.put(s.key(), r);
            if (r != null && r.get("error") != null) errors.add(s.key() + ": " + r.get("error"));
        }
        resp.put("specs", results);
        resp.put("errors", errors);
        resp.put("elapsedMs", (System.nanoTime() - startNs) / 1_000_000);
        return resp;
    }

    @Setter
    @Getter
    public static class SelfMetricDescriptor {
        private String metric;
        private String category;
        private String meterType;
        private String gcName;
        private Double lastValue;
        private Instant lastTime;

    }
}
