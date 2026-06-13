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
        StringBuilder flux = new StringBuilder();
        flux.append("from(bucket: \"").append(logBucket).append("\")\n");
        flux.append("  |> range(start: ").append(startTime).append(", stop: ").append(endTime).append(")\n");
        flux.append("  |> filter(fn: (r) => r._measurement == \"app_log\")\n");
        if (appid != null) {
            flux.append("  |> filter(fn: (r) => r[\"appid\"] == \"").append(appid).append("\")\n");
        }
        if (level != null) {
            flux.append("  |> filter(fn: (r) => r[\"level\"] == \"").append(level).append("\")\n");
        }
        flux.append("  |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n");
        flux.append("  |> sort(columns: [\"_time\"], desc: true)\n");
        flux.append("  |> limit(n: ").append(limit).append(")\n");

        log.info("[spring-watch: InfluxDB查询日志 - appid={}, level={}, range={}~{}, limit={}]",
                appid, level, startTime, endTime, limit);

        QueryApi queryApi = influxDBClient.getQueryApi();
        List<FluxTable> tables = queryApi.query(flux.toString(), influxOrg);

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
        log.info("[spring-watch: InfluxDB查询日志完成 - appid={}, level={}, rows={}]",
                appid, level, results.size());
        return results;
    }
}
