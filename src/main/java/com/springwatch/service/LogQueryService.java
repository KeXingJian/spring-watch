package com.springwatch.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxTable;
import com.influxdb.query.FluxRecord;
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
public class LogQueryService {

    private final InfluxDBClient influxDBClient;

    @Value("${influxdb.log-bucket}")
    private String logBucket;

    @Value("${influxdb.org}")
    private String influxOrg;

    public List<Map<String, Object>> queryLogs(Long appid, String level,
                                                Instant startTime, Instant endTime, int limit) {
        return search(appid, null, level, startTime, endTime, limit);
    }

    /**
     * kxj: 关键字检索-支持appid/level/keyword过滤,在message+throwable字段内匹配
     */
    public List<Map<String, Object>> search(Long appid, String keyword, String level,
                                             Instant startTime, Instant endTime, int limit) {
        StringBuilder flux = new StringBuilder();
        flux.append("from(bucket: \"").append(logBucket).append("\")\n");
        flux.append("  |> range(start: ").append(startTime).append(", stop: ").append(endTime).append(")\n");
        flux.append("  |> filter(fn: (r) => r._measurement == \"app_log\")\n");
        if (appid != null) {
            flux.append("  |> filter(fn: (r) => r[\"appid\"] == \"").append(appid).append("\")\n");
        }
        if (level != null && !level.isBlank()) {
            flux.append("  |> filter(fn: (r) => r[\"level\"] == \"").append(escape(level)).append("\")\n");
        }
        flux.append("  |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n");
        if (keyword != null && !keyword.isBlank()) {
            String kw = escape(keyword);
            flux.append("  |> filter(fn: (r) => (exists r.message and strings.containsStr(v: r.message, substr: \"")
                    .append(kw)
                    .append("\")) or (exists r.throwable and strings.containsStr(v: r.throwable, substr: \"")
                    .append(kw)
                    .append("\")))\n");
        }
        flux.append("  |> sort(columns: [\"_time\"], desc: true)\n");
        flux.append("  |> limit(n: ").append(limit).append(")\n");

        if (keyword != null && !keyword.isBlank()) {
            flux.insert(0, "import \"strings\"\n");
        }

        log.info("[spring-watch: InfluxDB查询日志 - appid={}, level={}, keyword={}, range={}~{}, limit={}]",
                appid, level, keyword, startTime, endTime, limit);

        return executeAndCollect(flux.toString());
    }

    /**
     * kxj: 按traceId串联日志-Trace上下文回查
     */
    public List<Map<String, Object>> findByTraceId(String traceId, Instant startTime, Instant endTime, int limit) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        StringBuilder flux = new StringBuilder();
        flux.append("from(bucket: \"").append(logBucket).append("\")\n");
        flux.append("  |> range(start: ").append(startTime).append(", stop: ").append(endTime).append(")\n");
        flux.append("  |> filter(fn: (r) => r._measurement == \"app_log\")\n");
        flux.append("  |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n");
        flux.append("  |> filter(fn: (r) => exists r.traceId and r.traceId == \"").append(escape(traceId)).append("\")\n");
        flux.append("  |> sort(columns: [\"_time\"], desc: false)\n");
        flux.append("  |> limit(n: ").append(limit).append(")\n");

        log.info("[spring-watch: InfluxDB按traceId查询 - traceId={}, range={}~{}, limit={}]",
                traceId, startTime, endTime, limit);
        return executeAndCollect(flux.toString());
    }

    /**
     * kxj: 按fingerprint查询同模式日志样本
     */
    public List<Map<String, Object>> findByFingerprint(Long appid, String fingerprint,
                                                        Instant startTime, Instant endTime, int limit) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return List.of();
        }
        StringBuilder flux = new StringBuilder();
        flux.append("from(bucket: \"").append(logBucket).append("\")\n");
        flux.append("  |> range(start: ").append(startTime).append(", stop: ").append(endTime).append(")\n");
        flux.append("  |> filter(fn: (r) => r._measurement == \"app_log\")\n");
        if (appid != null) {
            flux.append("  |> filter(fn: (r) => r[\"appid\"] == \"").append(appid).append("\")\n");
        }
        flux.append("  |> filter(fn: (r) => r[\"fingerprint\"] == \"").append(escape(fingerprint)).append("\")\n");
        flux.append("  |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n");
        flux.append("  |> sort(columns: [\"_time\"], desc: true)\n");
        flux.append("  |> limit(n: ").append(limit).append(")\n");

        log.info("[spring-watch: InfluxDB按fingerprint查询 - appid={}, fingerprint={}, limit={}]",
                appid, fingerprint, limit);
        return executeAndCollect(flux.toString());
    }

    private List<Map<String, Object>> executeAndCollect(String flux) {
        QueryApi queryApi = influxDBClient.getQueryApi();
        List<FluxTable> tables;
        try {
            tables = queryApi.query(flux, influxOrg);
        } catch (Exception e) {
            log.warn("[spring-watch: InfluxDB查询失败 - error={}]", e.getMessage());
            return List.of();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", record.getTime());
                record.getValues().forEach((k, v) -> {
                    if (k.startsWith("_") || "result".equals(k) || "table".equals(k)) {
                        return;
                    }
                    row.put(k, v);
                });
                results.add(row);
            }
        }
        log.info("[spring-watch: InfluxDB查询完成 - rows={}]", results.size());
        return results;
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
