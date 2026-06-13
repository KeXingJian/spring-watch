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
public class MetricQueryService {

    private final InfluxDBClient influxDBClient;

    @Value("${influxdb.metrics-bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String influxOrg;

    public List<Map<String, Object>> queryMetrics(String appName, String metricName,
                                                   Instant start, Instant stop) {
        StringBuilder flux = new StringBuilder();
        flux.append("from(bucket: \"").append(bucket).append("\")\n");
        flux.append("  |> range(start: ").append(start).append(", stop: ").append(stop).append(")\n");
        flux.append("  |> filter(fn: (r) => r[\"_measurement\"] == \"springboot_metrics\")\n");
        if (appName != null) {
            flux.append("  |> filter(fn: (r) => r[\"app\"] == \"").append(appName).append("\")\n");
        }
        if (metricName != null) {
            flux.append("  |> filter(fn: (r) => r[\"metric\"] == \"").append(metricName).append("\")\n");
        }

        log.info("[spring-watch: InfluxDB查询 - app={}, metric={}, range={}~{}]",
                appName, metricName, start, stop);

        QueryApi queryApi = influxDBClient.getQueryApi();
        List<FluxTable> tables = queryApi.query(flux.toString(), influxOrg);

        List<Map<String, Object>> results = new ArrayList<>();
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", record.getTime());
                row.put("measurement", record.getMeasurement());
                row.put("field", record.getField());
                row.put("value", record.getValue());
                record.getValues().forEach((k, v) -> {
                    if (k.startsWith("_") || "result".equals(k) || "table".equals(k)) {
                        return;
                    }
                    row.put(k, v);
                });
                results.add(row);
            }
        }
        log.info("[spring-watch: InfluxDB查询完成 - app={}, metric={}, records={}]",
                appName, metricName, results.size());
        return results;
    }
}