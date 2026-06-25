package com.springwatch.service;

import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxTable;
import com.influxdb.query.FluxRecord;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricQueryService {

    private static final String MEASUREMENT = "springboot_metrics";

    private final QueryApi queryApi;
    private final MeterRegistry meterRegistry;

    @Value("${influxdb.metrics-bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String influxOrg;

    private Timer latestTimer;
    private Timer seriesTimer;
    private Timer groupedTimer;
    private Timer histogramTimer;
    private Counter emptyCounter;
    private Counter failCounter;

    @PostConstruct
    void init() {
        this.latestTimer = Timer.builder("spring.watch.metric.query.latest")
                .description("查询最新指标").register(meterRegistry);
        this.seriesTimer = Timer.builder("spring.watch.metric.query.series")
                .description("查询时序指标").register(meterRegistry);
        this.groupedTimer = Timer.builder("spring.watch.metric.query.grouped")
                .description("查询分组指标").register(meterRegistry);
        this.histogramTimer = Timer.builder("spring.watch.metric.query.histogram")
                .description("查询直方图分位").register(meterRegistry);
        this.emptyCounter = Counter.builder("spring.watch.metric.query.empty_result")
                .description("指标查询返回空集").register(meterRegistry);
        this.failCounter = Counter.builder("spring.watch.metric.query.fail")
                .description("指标查询失败").register(meterRegistry);
    }

    public List<MetricDescriptor> listAvailable(Long appid) {
        long startNs = System.nanoTime();
        try {
            String flux = String.format(
                    "from(bucket: \"%s\")\n" +
                            "  |> range(start: -24h)\n" +
                            "  |> filter(fn: (r) => r._measurement == \"%s\" and r.appid == \"%s\")\n" +
                            "  |> group(columns: [\"metric\"])\n" +
                            "  |> last()\n" +
                            "  |> keep(columns: [\"_value\", \"_time\", \"metric\"])",
                    bucket, MEASUREMENT, appid);
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            List<MetricDescriptor> result = new ArrayList<>();
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    String metric = (String) record.getValueByKey("metric");
                    if (metric == null) continue;
                    MetricDescriptor d = new MetricDescriptor();
                    d.setMetric(metric);
                    Object v = record.getValue();
                    if (v instanceof Number n) d.setLastValue(n.doubleValue());
                    Object t = record.getValueByKey("_time");
                    if (t instanceof Instant ins) d.setLastTime(ins);
                    result.add(d);
                }
            }
            if (result.isEmpty()) emptyCounter.increment();
            return result;
        } catch (Exception e) {
            failCounter.increment();
            log.warn("[spring-watch: 指标列表查询失败 - appid={}, error={}]", appid, e.getMessage());
            return List.of();
        } finally {
            latestTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    public Map<String, Object> queryLatest(Long appid, String metric, Map<String, String> tagFilters) {
        long startNs = System.nanoTime();
        try {
            String tagFilterClause = buildTagFilter(tagFilters);
            String flux = String.format(
                    "from(bucket: \"%s\")\n" +
                            "  |> range(start: -1h)\n" +
                            "  |> filter(fn: (r) => r._measurement == \"%s\"\n" +
                            "                    and r.appid == \"%s\"\n" +
                            "                    and r._field == \"value\"\n" +
                            "                    and r.metric == \"%s\"%s)\n" +
                            "  |> sort(columns: [\"_time\"], desc: true)\n" +
                            "  |> limit(n: 500)",
                    bucket, MEASUREMENT, appid, metric, tagFilterClause);
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            log.trace("[kxj: queryLatest - 推tagFilter到Flux,appid={},metric={},tagFilterClause={},tables={}]",
                    appid, metric, tagFilterClause, tables.size());
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
                    collectTags(record, tags, "metric", "appid", "_field", "_measurement", "_start", "_stop", "_value", "_time", "result", "table", "host");
                    row.put("tags", tags);
                    latestByTags.put(key, row);
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>(latestByTags.values());
            if (rows.isEmpty()) emptyCounter.increment();
            log.trace("{}",rows);
            return Map.of("rows", rows, "count", rows.size());
        } catch (Exception e) {
            failCounter.increment();
            log.warn("[spring-watch: latest查询失败 - appid={}, metric={}, error={}]", appid, metric, e.getMessage());
            return Map.of("rows", List.of(), "count", 0, "error", e.getMessage());
        } finally {
            latestTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    public Map<String, Object> querySeries(Long appid, String metric, Instant from, Instant to,
                                           String agg, String every, Map<String, String> tagFilters) {
        long startNs = System.nanoTime();
        try {
            String aggLower = agg == null ? "mean" : agg.toLowerCase();
            String aggFn = switch (aggLower) {
                case "max" -> "max";
                case "min" -> "min";
                case "sum" -> "sum";
                case "last" -> "last";
                case "rate" -> "mean";
                default -> "mean";
            };
            // rate 是给 counter 用的,先 derivative(unit: 1s) 转成 1/s,再按窗口聚合均值
            String rateStep = "rate".equals(aggLower) ? "|> derivative(unit: 1s, nonNegative: true)\n" : "";
            String window = (every == null || every.isBlank()) ? "30s" : every;
            String filter = buildTagFilter(tagFilters);
            String flux = String.format(
                    "from(bucket: \"%s\")\n" +
                            "  |> range(start: %s, stop: %s)\n" +
                            "  |> filter(fn: (r) => r._measurement == \"%s\"\n" +
                            "                    and r.appid == \"%s\"\n" +
                            "                    and r._field == \"value\"\n" +
                            "                    and r.metric == \"%s\"%s)\n" +
                            "%s" +
                            "  |> aggregateWindow(every: %s, fn: %s, createEmpty: false)\n" +
                            "  |> yield(name: \"series\")",
                    bucket, formatInstant(from), formatInstant(to), MEASUREMENT, appid, metric, filter, rateStep, window, aggFn);
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            List<Map<String, Object>> resultSeries = new ArrayList<>();
            for (FluxTable table : tables) {
                String seriesName = metric;
                List<Map.Entry<String, Object>> extraTags = new ArrayList<>();
                List<Map<String, Object>> points = new ArrayList<>();
                for (FluxRecord record : table.getRecords()) {
                    if (points.isEmpty()) {
                        java.util.Set<String> excluded = java.util.Set.of("metric", "appid", "_field", "_measurement", "_start", "_stop", "_value", "_time", "result", "table");
                        for (java.util.Map.Entry<String, Object> e : record.getValues().entrySet()) {
                            if (excluded.contains(e.getKey())) continue;
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
            if (resultSeries.isEmpty()) emptyCounter.increment();
            return Map.of("series", resultSeries, "count", resultSeries.size());
        } catch (Exception e) {
            failCounter.increment();
            log.warn("[spring-watch: series查询失败 - appid={}, metric={}, error={}]", appid, metric, e.getMessage());
            return Map.of("series", List.of(), "count", 0, "error", e.getMessage());
        } finally {
            seriesTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    public Map<String, Object> queryGrouped(Long appid, String metric, String groupBy, String agg) {
        long startNs = System.nanoTime();
        try {
            String aggLower = agg == null ? "last" : agg.toLowerCase();
            String rateStep = "rate".equals(aggLower) ? "|> derivative(unit: 1s, nonNegative: true)\n" : "";
            String[] groupCols = parseGroupBy(groupBy);
            String groupColumnsLiteral = Arrays.stream(groupCols)
                    .map(c -> "\"" + c.replace("\"", "\\\"") + "\"")
                    .collect(Collectors.joining(", "));
            String flux = String.format(
                    "from(bucket: \"%s\")\n" +
                            "  |> range(start: -5m)\n" +
                            "  |> filter(fn: (r) => r._measurement == \"%s\"\n" +
                            "                    and r.appid == \"%s\"\n" +
                            "                    and r._field == \"value\"\n" +
                            "                    and r.metric == \"%s\")\n" +
                            "%s" +
                            "  |> group(columns: [%s])\n" +
                            "  |> last()",
                    bucket, MEASUREMENT, appid, metric, rateStep, groupColumnsLiteral);
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            List<Map<String, Object>> groups = new ArrayList<>();
            for (FluxTable table : tables) {
                Map<String, String> tagVals = new LinkedHashMap<>();
                Double value = null;
                for (FluxRecord record : table.getRecords()) {
                    for (String col : groupCols) {
                        Object g = record.getValueByKey(col);
                        if (g != null) tagVals.put(col, g.toString());
                    }
                    Object v = record.getValue();
                    if (v instanceof Number n) value = n.doubleValue();
                }
                String compositeKey = tagVals.values().stream().collect(Collectors.joining("|"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("group", compositeKey.isEmpty() ? "default" : compositeKey);
                row.put("tags", tagVals);
                row.put("value", value);
                groups.add(row);
            }
            if (groups.isEmpty()) emptyCounter.increment();
            return Map.of("groups", groups, "count", groups.size());
        } catch (Exception e) {
            failCounter.increment();
            log.warn("[spring-watch: grouped查询失败 - appid={}, metric={}, groupBy={}, error={}]",
                    appid, metric, groupBy, e.getMessage());
            return Map.of("groups", List.of(), "count", 0, "error", e.getMessage());
        } finally {
            groupedTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    private static String[] parseGroupBy(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) return new String[]{"device"};
        String[] cols = Arrays.stream(groupBy.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        return cols.length == 0 ? new String[]{"device"} : cols;
    }

    public Map<String, Object> queryHistogramQuantile(Long appid, String metric, List<Double> quantiles,
                                                      Instant from, Instant to, String every,
                                                      Map<String, String> tagFilters) {
        long startNs = System.nanoTime();
        try {
            String window = (every == null || every.isBlank()) ? "30s" : every;
            String bucketMetric = metric + "_bucket";
            String countMetric = metric + "_count";
            String tagClause = buildTagFilter(tagFilters);
            String flux = String.format(
                    "from(bucket: \"%s\")\n" +
                            "  |> range(start: %s, stop: %s)\n" +
                            "  |> filter(fn: (r) => r._measurement == \"%s\"\n" +
                            "                    and r.appid == \"%s\"\n" +
                            "                    and r._field == \"value\"\n" +
                            "                    and (r.metric == \"%s\" or r.metric == \"%s\")%s)\n" +
                            "  |> aggregateWindow(every: %s, fn: last, createEmpty: false)",
                    bucket, formatInstant(from), formatInstant(to), MEASUREMENT, appid, bucketMetric, countMetric, tagClause, window);
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            java.util.Set<String> excludeKeys = java.util.Set.of(
                    "metric", "appid", "_field", "_measurement", "_start", "_stop", "_value", "_time", "result", "table", "le");
            Map<String, Map<String, Object>> slots = new LinkedHashMap<>();
            for (FluxTable table : tables) {
                for (FluxRecord r : table.getRecords()) {
                    String m = (String) r.getValueByKey("metric");
                    if (m == null) continue;
                    Object tObj = r.getValueByKey("_time");
                    if (tObj == null) continue;
                    String t = tObj.toString();

                    StringBuilder tagKey = new StringBuilder();
                    Map<String, Object> tagMap = new LinkedHashMap<>();
                    for (java.util.Map.Entry<String, Object> e : r.getValues().entrySet()) {
                        String k = e.getKey();
                        if (excludeKeys.contains(k) || k.startsWith("_")) continue;
                        if (e.getValue() == null) continue;
                        tagKey.append(k).append("=").append(e.getValue()).append("|");
                        tagMap.put(k, e.getValue());
                    }
                    String slotKey = tagKey + "@" + t;
                    Map<String, Object> slot = slots.computeIfAbsent(slotKey, k -> {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("t", t);
                        s.put("tags", tagMap);
                        s.put("le", new LinkedHashMap<String, Double>());
                        s.put("count", null);
                        return s;
                    });

                    if (m.equals(bucketMetric)) {
                        Object le = r.getValueByKey("le");
                        if (le == null) continue;
                        Object v = r.getValue();
                        if (v instanceof Number n) {
                            ((Map<String, Double>) slot.get("le")).put(le.toString(), n.doubleValue());
                        }
                    } else if (m.equals(countMetric)) {
                        Object v = r.getValue();
                        if (v instanceof Number n) slot.put("count", n.doubleValue());
                    }
                }
            }
            List<Map<String, Object>> outPoints = new ArrayList<>();
            for (Map<String, Object> slot : slots.values()) {
                Double count = (Double) slot.get("count");
                if (count == null || count <= 0) continue;
                Map<String, Double> leMap = (Map<String, Double>) slot.get("le");
                if (leMap.isEmpty()) continue;
                List<Map.Entry<Double, Double>> sorted = leMap.entrySet().stream()
                        .map(en -> {
                            Double le = parseLe(en.getKey());
                            return le == null ? null : Map.entry(le, en.getValue());
                        })
                        .filter(java.util.Objects::nonNull)
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("t", slot.get("t"));
                point.put("tags", slot.get("tags"));
                for (Double q : quantiles) {
                    point.put("q" + (int) (q * 100), quantile(sorted, count, q));
                }
                outPoints.add(point);
            }
            if (outPoints.isEmpty()) emptyCounter.increment();
            return Map.of("points", outPoints, "count", outPoints.size());
        } catch (Exception e) {
            failCounter.increment();
            log.warn("[spring-watch: histogram-quantile查询失败 - appid={}, metric={}, error={}]",
                    appid, metric, e.getMessage());
            return Map.of("points", List.of(), "count", 0, "error", e.getMessage());
        } finally {
            histogramTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    private static Double quantile(List<Map.Entry<Double, Double>> sorted, double totalCount, double q) {
        if (sorted.isEmpty()) return null;
        double target = q * totalCount;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<Double, Double> e = sorted.get(i);
            if (e.getValue() >= target) {
                double prevCum = (i == 0) ? 0.0 : sorted.get(i - 1).getValue();
                double le = e.getKey();
                double nextCum = e.getValue();
                double nextLe = (i + 1 < sorted.size()) ? sorted.get(i + 1).getKey() : le;
                if (nextCum == prevCum) return le;
                double frac = (target - prevCum) / (nextCum - prevCum);
                return le + frac * (nextLe - le);
            }
        }
        return sorted.get(sorted.size() - 1).getKey();
    }

    private static Double parseLe(String le) {
        if (le.equals("+Inf")) return Double.POSITIVE_INFINITY;
        try {
            return Double.parseDouble(le);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatInstant(Instant t) {
        return "time(v: " + t.toString() + ")";
    }

    private static String buildTagFilter(Map<String, String> tagFilters) {
        if (tagFilters == null || tagFilters.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : tagFilters.entrySet()) {
            if (e.getValue() == null) continue;
            sb.append("\n                    and r.")
                    .append(e.getKey())
                    .append(" == \"")
                    .append(e.getValue().replace("\"", "\\\""))
                    .append("\"");
        }
        return sb.toString();
    }

    private static boolean matchTagFilters(FluxRecord record, Map<String, String> tagFilters) {
        if (tagFilters == null || tagFilters.isEmpty()) return true;
        for (Map.Entry<String, String> e : tagFilters.entrySet()) {
            if (e.getValue() == null) continue;
            Object v = record.getValueByKey(e.getKey());
            if (v == null) return false;
            if (!String.valueOf(v).equals(e.getValue())) return false;
        }
        return true;
    }

    private static String buildTagKey(FluxRecord record, Map<String, String> filterKeys) {
        java.util.Set<String> filterSet = filterKeys == null ? java.util.Set.of() : filterKeys.keySet();
        java.util.Set<String> exclude = java.util.Set.of("metric", "appid", "_field", "_measurement", "_start", "_stop", "_value", "_time", "result", "table");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : record.getValues().entrySet()) {
            if (exclude.contains(e.getKey())) continue;
            if (e.getKey().startsWith("_")) continue;
            if (!filterSet.contains(e.getKey())) continue;
            sb.append(e.getKey()).append("=").append(e.getValue()).append("|");
        }
        return sb.toString();
    }

    private static String buildAllTagsKey(FluxRecord record) {
        java.util.Set<String> exclude = java.util.Set.of("metric", "appid", "_field", "_measurement", "_start", "_stop", "_value", "_time", "result", "table", "host");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : record.getValues().entrySet()) {
            if (exclude.contains(e.getKey())) continue;
            if (e.getKey().startsWith("_")) continue;
            if (e.getValue() == null) continue;
            sb.append(e.getKey()).append("=").append(e.getValue()).append("|");
        }
        return sb.toString();
    }

    private static int sumRecords(List<FluxTable> tables) {
        int n = 0;
        for (FluxTable t : tables) n += t.getRecords().size();
        return n;
    }

    private static void collectTags(FluxRecord record, Map<String, Object> out, String... exclude) {
        java.util.Set<String> ex = java.util.Set.of(exclude);
        for (java.util.Map.Entry<String, Object> e : record.getValues().entrySet()) {
            if (ex.contains(e.getKey())) continue;
            if (e.getKey().startsWith("_")) continue;
            Object v = e.getValue();
            if (v != null) out.put(e.getKey(), v);
        }
    }

    public static class MetricDescriptor {
        private String metric;
        private Double lastValue;
        private Instant lastTime;

        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        public Double getLastValue() { return lastValue; }
        public void setLastValue(Double lastValue) { this.lastValue = lastValue; }
        public Instant getLastTime() { return lastTime; }
        public void setLastTime(Instant lastTime) { this.lastTime = lastTime; }
    }
}
