package com.springwatch.service;

import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
                    "from(bucket: \"%s\")\n" +
                            "  |> range(start: -24h)\n" +
                            "  |> filter(fn: (r) => r._measurement == \"%s\" and r.appid == \"%s\")\n" +
                            "  |> group(columns: [\"category\"])\n" +
                            "  |> distinct(column: \"category\")\n" +
                            "  |> keep(columns: [\"category\"])",
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
            log.warn("[spring-watch: 自监控 category 查询失败 - error={}]", e.getMessage());
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
                    "from(bucket: \"%s\")\n" +
                            "  |> range(start: -24h)\n" +
                            "  |> filter(fn: (r) => r._measurement == \"%s\"\n" +
                            "                    and r.appid == \"%s\"\n" +
                            "                    and r._field == \"value\"",
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
            log.warn("[spring-watch: 自监控 metric 列表查询失败 - category={}, tags={}, error={}]",
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
                    "from(bucket: \"%s\")\n" +
                            "  |> range(start: %s, stop: %s)\n" +
                            "  |> filter(fn: (r) => r._measurement == \"%s\"\n" +
                            "                    and r.appid == \"%s\"\n" +
                            "                    and r._field == \"%s\"%s)\n" +
                            "%s" +
                            "  |> aggregateWindow(every: %s, fn: %s, createEmpty: false)\n" +
                            "  |> yield(name: \"series\")",
                    bucket, formatInstant(from), formatInstant(to), MEASUREMENT, APPID_SELF,
                    escape(targetField), filter, rateStep, window, aggFn);
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            return parseSeries(tables, metric, tagFilters);
        } catch (Exception e) {
            seriesFailCounter.increment();
            log.warn("[spring-watch: 自监控 series 查询失败 - metric={}, error={}]", metric, e.getMessage());
            return Map.of("series", List.of(), "count", 0, "error", e.getMessage());
        } finally {
            seriesTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 取某指标最新一帧（含完整标签与时间戳），用于卡片实时值。
     */
    public Map<String, Object> queryLatest(String category, String metric, Map<String, String> tagFilters) {
        long startNs = System.nanoTime();
        try {
            String filter = buildTagFilter(category, metric, tagFilters);
            String flux = String.format(
                    "from(bucket: \"%s\")\n" +
                            "  |> range(start: -1h)\n" +
                            "  |> filter(fn: (r) => r._measurement == \"%s\"\n" +
                            "                    and r.appid == \"%s\"\n" +
                            "                    and r._field == \"value\"%s)\n" +
                            "  |> sort(columns: [\"_time\"], desc: true)\n" +
                            "  |> limit(n: 50)",
                    bucket, MEASUREMENT, APPID_SELF, filter);
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
            log.warn("[spring-watch: 自监控 latest 查询失败 - metric={}, error={}]", metric, e.getMessage());
            return Map.of("rows", List.of(), "count", 0, "error", e.getMessage());
        } finally {
            latestTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 应用指标式 series 解析（与 MetricQueryService.querySeries 行为一致）。
     * 多个 series（按 tag 区分）都会被返回。
     */
    private static Map<String, Object> parseSeries(List<FluxTable> tables, String metric,
                                                    Map<String, String> tagFilters) {
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

    public static class SelfMetricDescriptor {
        private String metric;
        private String category;
        private String meterType;
        private String gcName;
        private Double lastValue;
        private Instant lastTime;

        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getMeterType() { return meterType; }
        public void setMeterType(String meterType) { this.meterType = meterType; }
        public String getGcName() { return gcName; }
        public void setGcName(String gcName) { this.gcName = gcName; }
        public Double getLastValue() { return lastValue; }
        public void setLastValue(Double lastValue) { this.lastValue = lastValue; }
        public Instant getLastTime() { return lastTime; }
        public void setLastTime(Instant lastTime) { this.lastTime = lastTime; }
    }
}
