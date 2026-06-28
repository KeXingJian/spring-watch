package com.springwatch.monitor;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class InfrastructureMetricsCollector {

    private final WriteApi writeApi;
    private final MeterRegistry meterRegistry;
    private final String influxOrg;
    private final String infraBucket;
    private final WriteParameters infraWriteParameters;
    private final String influxUrl;

    @Value("${spring-watch.infra-metrics.poll-interval-sec:30}")
    private long pollIntervalSec;

    @Value("${spring-watch.infra-metrics.enabled:true}")
    private boolean enabled;

    @Value("${spring-watch.infra-metrics.request-timeout-ms:5000}")
    private int requestTimeoutMs;

    @Value("${spring-watch.influxdb.admin-token:}")
    private String adminToken;

    private ScheduledExecutorService scheduler;
    private Counter pollOkCounter;
    private Counter pollFailCounter;
    private final AtomicLong lastSuccessEpochMs = new AtomicLong(0L);
    private final AtomicLong lastPollEpochMs = new AtomicLong(0L);
    private volatile String lastError = "";

    private final Map<String, String> metricToMeasurement = new HashMap<>();
    private final List<MetricMapping> mappings = new ArrayList<>();

    public InfrastructureMetricsCollector(WriteApi writeApi,
                                          MeterRegistry meterRegistry,
                                          @Value("${influxdb.org}") String org,
                                          @Value("${influxdb.infra-bucket:infra_metrics}") String infraBucket,
                                          @Value("${influxdb.url}") String url) {
        this.writeApi = writeApi;
        this.meterRegistry = meterRegistry;
        this.influxOrg = org;
        this.infraBucket = infraBucket;
        this.influxUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.infraWriteParameters = new WriteParameters(infraBucket, org, WritePrecision.NS);
    }

    @PostConstruct
    void start() {
        this.pollOkCounter = Counter.builder("spring.watch.infra.poll.ok")
                .description("infra 指标采集成功次数")
                .register(meterRegistry);
        this.pollFailCounter = Counter.builder("spring.watch.infra.poll.fail")
                .description("infra 指标采集失败次数")
                .register(meterRegistry);

        Gauge.builder("spring.watch.infra.last_poll_epoch_ms", lastPollEpochMs, AtomicLong::doubleValue)
                .description("最近一次 infra 采集 epoch ms")
                .register(meterRegistry);
        Gauge.builder("spring.watch.infra.last_success_epoch_ms", lastSuccessEpochMs, AtomicLong::doubleValue)
                .description("最近一次 infra 采集成功 epoch ms")
                .register(meterRegistry);

        if (!enabled) {
            log.info("[spring-watch: InfrastructureMetricsCollector 禁用]");
            return;
        }
        initMappings();
        ThreadFactory tf = Thread.ofVirtual().name("infra-metrics-", 0).factory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        scheduler.scheduleWithFixedDelay(this::poll, 10L, pollIntervalSec, TimeUnit.SECONDS);
        log.info("[spring-watch: InfrastructureMetricsCollector 启动 - interval={}s, url={}, mappings={}",
                pollIntervalSec, influxUrl, mappings.size());
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    public String getInfraBucket() {
        return infraBucket;
    }

    public long getLastSuccessEpochMs() {
        return lastSuccessEpochMs.get();
    }

    public long getLastPollEpochMs() {
        return lastPollEpochMs.get();
    }

    public String getLastError() {
        return lastError;
    }

    private void initMappings() {
        mappings.clear();
        metricToMeasurement.clear();
        addMapping("go_runtime", "go_runtime_", "go_runtime.");
        addMapping("go_goroutines", "go_goroutines", "go_goroutines");
        addMapping("go_info", "go_info_", "go_info.");
        addMapping("go_gc_duration_seconds", "go_gc_duration_seconds_", "go_gc_duration_seconds.");
        addMapping("go_memstats", "go_memstats_", "go_memstats.");
        addMapping("storage_engine", "storage_engine_", "storage.");
        addMapping("tsm1_engine", "tsm1_engine_", "tsm1_engine.");
        addMapping("tsm1_cache_disk_bytes", "tsm1_cache_disk_bytes", "tsm1_cache_disk_bytes");
        addMapping("tsm1_cache_mem_bytes", "tsm1_cache_mem_bytes", "tsm1_cache_mem_bytes");
        addMapping("tsm1_cache_snapshot_count", "tsm1_cache_snapshot_count", "tsm1_cache_snapshot_count");
        addMapping("tsm1_wal", "tsm1_wal_", "tsm1_wal.");
        addMapping("httpd", "httpd_", "httpd.");
        addMapping("query_control", "query_control_", "query_control.");
        addMapping("query", "query_", "query.");
        addMapping("write", "write_", "write.");
        addMapping("points", "points_", "points.");
        addMapping("task_executor", "task_executor_", "task_executor.");
        addMapping("subscriber", "subscriber_", "subscriber.");
    }

    private void addMapping(String measurement, String metricPrefix, String outPrefix) {
        mappings.add(new MetricMapping(measurement, metricPrefix, outPrefix));
    }

    private void poll() {
        lastPollEpochMs.set(System.currentTimeMillis());
        try {
            String text = fetchPrometheus();
            if (text == null || text.isBlank()) {
                lastError = "empty response";
                pollFailCounter.increment();
                return;
            }
            Map<String, Double> gauges = parsePrometheus(text);
            if (gauges.isEmpty()) {
                lastError = "no metrics parsed";
                pollFailCounter.increment();
                return;
            }
            long tsNs = System.currentTimeMillis() * 1_000_000L;
            List<Point> points = new ArrayList<>(gauges.size());
            for (Map.Entry<String, Double> e : gauges.entrySet()) {
                Point p = Point.measurement("infra_metrics")
                        .addTag("component", "influxdb")
                        .addTag("metric", e.getKey())
                        .addField("value", e.getValue())
                        .time(tsNs, WritePrecision.NS);
                points.add(p);
            }
            writeApi.writePoints(points, infraWriteParameters);
            lastSuccessEpochMs.set(System.currentTimeMillis());
            lastError = "";
            pollOkCounter.increment();
        } catch (Throwable t) {
            pollFailCounter.increment();
            lastError = t.getClass().getSimpleName() + ":" + t.getMessage();
            log.warn("[spring-watch: infra 指标采集失败 - error={}]", t.getMessage());
        }
    }

    private String fetchPrometheus() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(influxUrl + "/metrics").toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(requestTimeoutMs);
        conn.setReadTimeout(requestTimeoutMs);
        if (adminToken != null && !adminToken.isBlank()) {
            conn.setRequestProperty("Authorization", "Token " + adminToken);
        }
        conn.setRequestProperty("Accept", "text/plain");
        int code = conn.getResponseCode();
        if (code != 200) {
            try (InputStream es = conn.getErrorStream()) {
                String err = es == null ? "" : new String(es.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException("HTTP " + code + " from " + influxUrl + "/metrics");
        }
        try (InputStream is = conn.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private Map<String, Double> parsePrometheus(String text) {
        Map<String, Double> out = new HashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace < 0) continue;
            String nameAndTags = line.substring(0, lastSpace);
            String valueStr = line.substring(lastSpace + 1);
            double value;
            try {
                value = Double.parseDouble(valueStr);
            } catch (NumberFormatException ignore) {
                continue;
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) continue;

            for (MetricMapping m : mappings) {
                if (!nameAndTags.startsWith(m.metricPrefix)) continue;
                String fieldPart = nameAndTags.substring(m.metricPrefix.length());
                int braceStart = fieldPart.indexOf('{');
                String field = braceStart > 0 ? fieldPart.substring(0, braceStart) : fieldPart;
                out.put(m.outPrefix + field, value);
                break;
            }
        }
        return out;
    }

    private record MetricMapping(String measurement, String metricPrefix, String outPrefix) {}
}
