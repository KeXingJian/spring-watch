package com.springwatch.service;

import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InfraMetricsQueryService {

    private final QueryApi queryApi;

    @Value("${influxdb.org}")
    private String influxOrg;

    @Value("${influxdb.infra-bucket:infra_metrics}")
    private String infraBucket;

    public List<String> listComponents() {
        return List.of("influxdb", "kafka");
    }

    public List<String> listMetrics(String component) {
        if (component == null || component.isBlank()) {
            return List.of();
        }
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: -24h)
                  |> filter(fn: (r) => r.component == "%s")
                  |> keep(columns: ["metric"])
                  |> distinct(column: "metric")
                  |> sort(columns: ["metric"])
                """, infraBucket, component);
        log.debug("[spring-watch: infra listMetrics flux] {}", flux.replace("\n", " | "));
        List<String> out = new ArrayList<>();
        try {
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            for (FluxTable t : tables) {
                for (FluxRecord r : t.getRecords()) {
                    Object m = r.getValueByKey("metric");
                    if (m != null) out.add(m.toString());
                }
            }
        } catch (Exception e) {
            log.warn("[spring-watch: infra listMetrics 失败 - component={}, error={}]", component, e.getMessage());
        }
        return out;
    }

    /**
     * 拉时序。
     * <p>
     * 设计要点：旧版先 {@code group(columns: ["topic", "partition", "group"])} 再
     * {@code aggregateWindow(...)}。该 query 用 curl POST raw / JSON 都能正常返回，但走
     * InfluxDB Java 客户端 7.2.0 时被 server 端 Flux 编译器报
     * {@code found unexpected argument except (Expected one of `columns` or `mode`)}，疑似
     * 客户端 7.2.0 与 server 2.7.12 在序列化 / 解析 group+aggregate 组合时的差异。
     * <p>
     * 因此改用更稳的写法：去掉前置 {@code group()} 调用。InfluxDB 查询时输入表已经按
     * 写入端的 (topic, partition, group) tag 切分成多个 series，{@code aggregateWindow}
     * 默认就是按 series（即 group key）独立聚合，按 topic/partition 分组的能力自动保留，
     * 且不会触发上面的解析问题。
     */
    public Map<String, Object> querySeries(String component, String metric, Instant from, Instant to, String every) {
        Instant fromInstant = from == null ? Instant.now().minusSeconds(3600) : from;
        Instant toInstant = to == null ? Instant.now() : to;
        String everyResolved = (every == null || every.isBlank()) ? "30s" : every;
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r.component == "%s" and r.metric == "%s")
                  |> aggregateWindow(every: %s, fn: last, createEmpty: false)
                  |> keep(columns: ["_time", "_value", "topic", "partition", "group"])
                """, infraBucket, fromInstant, toInstant, component, metric, everyResolved);
        log.debug("[spring-watch: infra querySeries flux - metric={}] {}", metric, flux.replace("\n", " | "));

        List<Map<String, Object>> seriesOut = new ArrayList<>();
        long totalPoints = 0L;
        try {
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            for (FluxTable t : tables) {
                if (t.getRecords().isEmpty()) continue;
                Map<String, Object> s = new LinkedHashMap<>();
                List<Map<String, Object>> points = new ArrayList<>();
                StringBuilder name = new StringBuilder();
                for (FluxRecord r : t.getRecords()) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("t", r.getTime());
                    Object v = r.getValue();
                    if (v instanceof Number n) p.put("v", n.doubleValue());
                    else p.put("v", v);
                    points.add(p);
                }
                Map<String, String> tagMap = new LinkedHashMap<>();
                FluxRecord first = t.getRecords().getFirst();
                for (String col : new String[]{"topic", "partition", "group"}) {
                    Object v = first.getValueByKey(col);
                    if (v != null) {
                        tagMap.put(col, v.toString());
                        if (!name.isEmpty()) name.append(" / ");
                        name.append(col).append("=").append(v);
                    }
                }
                s.put("name", !name.isEmpty() ? name.toString() : metric);
                s.put("tags", tagMap);
                s.put("points", points);
                seriesOut.add(s);
                totalPoints += points.size();
            }
        } catch (Exception e) {
            log.warn("[spring-watch: infra querySeries 失败 - metric={}, error={}]", metric, e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("component", component);
        result.put("metric", metric);
        result.put("from", fromInstant);
        result.put("to", toInstant);
        result.put("every", everyResolved);
        result.put("series", seriesOut);
        result.put("points", seriesOut.isEmpty() ? List.of() : seriesOut.getFirst().get("points"));
        result.put("totalPoints", totalPoints);
        return result;
    }

    public Map<String, Object> queryLatest(String component, String metric) {
        // 与 querySeries 同样的原因：去掉显式 group(columns:[...])，
        // 让 Flux 按每条 series（含原始 tag）独立取 last，保留 topic/partition 信息。
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: -10m)
                  |> filter(fn: (r) => r.component == "%s" and r.metric == "%s")
                  |> last()
                """, infraBucket, component, metric);
        log.debug("[spring-watch: infra queryLatest flux - metric={}] {}", metric, flux.replace("\n", " | "));

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            for (FluxTable t : tables) {
                if (t.getRecords().isEmpty()) continue;
                FluxRecord r = t.getRecords().getFirst();
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("t", r.getTime());
                Object v = r.getValue();
                if (v instanceof Number n) p.put("v", n.doubleValue());
                else p.put("v", v);
                Map<String, String> tagMap = new LinkedHashMap<>();
                StringBuilder name = new StringBuilder();
                for (String col : new String[]{"topic", "partition", "group"}) {
                    Object tv = r.getValueByKey(col);
                    if (tv != null) {
                        tagMap.put(col, tv.toString());
                        if (!name.isEmpty()) name.append(" / ");
                        name.append(col).append("=").append(tv);
                    }
                }
                p.put("name", !name.isEmpty() ? name.toString() : metric);
                p.put("tags", tagMap);
                items.add(p);
            }
        } catch (Exception e) {
            log.warn("[spring-watch: infra queryLatest 失败 - metric={}, error={}]", metric, e.getMessage());
        }
        if (items.isEmpty()) return result;
        result.put("items", items);
        result.put("value", items.getFirst().get("v"));
        return result;
    }

    public List<String> listInternalMeasurements() {
        String flux = """
                import "influxdata/influxdb/schema"
                schema.measurements(bucket: "_internal")
                """;
        List<String> out = new ArrayList<>();
        try {
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            for (FluxTable t : tables) {
                for (FluxRecord r : t.getRecords()) {
                    Object v = r.getValue();
                    if (v != null) out.add(v.toString());
                }
            }
        } catch (Exception e) {
            log.debug("[spring-watch: listInternalMeasurements 失败(token 可能无 _internal 读权限) - error={}]", e.getMessage());
        }
        return out;
    }
}
