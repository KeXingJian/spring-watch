package com.springwatch.web;

import com.springwatch.monitor.InfrastructureMetricsCollector;
import com.springwatch.service.InfraMetricsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/infra")
@RequiredArgsConstructor
public class InfraController {

    private final InfraMetricsQueryService queryService;
    private final InfrastructureMetricsCollector collector;

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("bucket", collector.getInfraBucket());
        r.put("lastPollEpochMs", collector.getLastPollEpochMs());
        r.put("lastSuccessEpochMs", collector.getLastSuccessEpochMs());
        r.put("lastError", collector.getLastError());
        return r;
    }

    @GetMapping("/components")
    public Map<String, Object> components() {
        return Map.of("components", queryService.listComponents());
    }

    @GetMapping("/internal-measurements")
    public Map<String, Object> internalMeasurements() {
        return Map.of("measurements", queryService.listInternalMeasurements());
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(@RequestParam String component) {
        List<String> ms = queryService.listMetrics(component);
        return Map.of("component", component, "metrics", ms, "count", ms.size());
    }

    @GetMapping("/series")
    public Map<String, Object> series(@RequestParam String component,
                                       @RequestParam String metric,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                       @RequestParam(required = false) String every) {
        return queryService.querySeries(component, metric, from, to, every);
    }

    @GetMapping("/latest")
    public Map<String, Object> latest(@RequestParam String component,
                                        @RequestParam String metric) {
        return queryService.queryLatest(component, metric);
    }
}
