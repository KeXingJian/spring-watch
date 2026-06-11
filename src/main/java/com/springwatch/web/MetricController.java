package com.springwatch.web;

import com.springwatch.model.dto.ApiResponse;
import com.springwatch.service.MetricQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricController {

    private final MetricQueryService metricQueryService;

    @GetMapping("/query")
    public ApiResponse<List<Map<String, Object>>> query(
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String metric,
            @RequestParam(defaultValue = "-1h") String start,
            @RequestParam(defaultValue = "now()") String stop) {
        Instant startTime = parseInstant(start, true);
        Instant stopTime = parseInstant(stop, false);
        return ApiResponse.ok(metricQueryService.queryMetrics(app, metric, startTime, stopTime));
    }

    private Instant parseInstant(String value, boolean isStart) {
        if (value.equals("now()")) {
            return Instant.now();
        }
        if (value.startsWith("-")) {
            long seconds = parseDuration(value);
            return Instant.now().minusSeconds(seconds);
        }
        return Instant.parse(value);
    }

    private long parseDuration(String value) {
        String v = value.substring(1);
        if (v.endsWith("h")) {
            return Long.parseLong(v.replace("h", "")) * 3600;
        }
        if (v.endsWith("m")) {
            return Long.parseLong(v.replace("m", "")) * 60;
        }
        if (v.endsWith("s")) {
            return Long.parseLong(v.replace("s", ""));
        }
        if (v.endsWith("d")) {
            return Long.parseLong(v.replace("d", "")) * 86400;
        }
        return Long.parseLong(v);
    }
}