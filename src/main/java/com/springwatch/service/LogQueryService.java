package com.springwatch.service;

import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxTable;
import com.influxdb.query.FluxRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogQueryService {

    private static final String CACHE_PREFIX = "log:query:cache:";

    private final QueryApi queryApi;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${influxdb.log-bucket}")
    private String logBucket;

    @Value("${influxdb.org}")
    private String influxOrg;

    @Value("${spring-watch.log.query.cache-ttl-seconds:15}")
    private long cacheTtlSeconds;

    @Value("${spring-watch.log.query.cache-enabled:true}")
    private boolean cacheEnabled;

    private Counter cacheHitCounter;
    private Counter cacheMissCounter;
    private Counter queryCounter;
    private Counter queryFailCounter;

    @PostConstruct
    void initMetrics() {
        this.cacheHitCounter = Counter.builder("spring.watch.log.query.cache_hit")
                .description("日志查询缓存命中次数")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("spring.watch.log.query.cache_miss")
                .description("日志查询缓存未命中次数")
                .register(meterRegistry);
        this.queryCounter = Counter.builder("spring.watch.log.query.influxdb")
                .description("日志查询 InfluxDB 调用次数")
                .register(meterRegistry);
        this.queryFailCounter = Counter.builder("spring.watch.log.query.fail")
                .description("日志查询 InfluxDB 失败次数")
                .register(meterRegistry);
    }

    public List<Map<String, Object>> queryLogs(Long appid, String level,
                                                Instant startTime, Instant endTime, int limit) {
        return search(appid, null, level, startTime, endTime, limit);
    }

    /**
     * kxj: 关键字检索-支持appid/level/keyword过滤,在message+throwable字段内匹配
     * P0 search 慢 - 加 Redis 结果缓存(短 TTL,与 InfluxDB 实时性折中)
     */
    public List<Map<String, Object>> search(Long appid, String keyword, String level,
                                             Instant startTime, Instant endTime, int limit) {
        String cacheKey = buildCacheKey("search", appid, keyword, level, startTime, endTime, limit);
        List<Map<String, Object>> cached = readCache(cacheKey);
        if (cached != null) {
            cacheHitCounter.increment();
            log.debug("[spring-watch: 日志查询缓存命中 - key={}, rows={}]", cacheKey, cached.size());
            return cached;
        }
        cacheMissCounter.increment();

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

        List<Map<String, Object>> result = executeAndCollect(flux.toString());
        writeCache(cacheKey, result);
        return result;
    }

    /**
     * kxj: 按traceId串联日志-Trace上下文回查
     */
    public List<Map<String, Object>> findByTraceId(String traceId, Instant startTime, Instant endTime, int limit) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        String cacheKey = buildCacheKey("trace", null, traceId, null, startTime, endTime, limit);
        List<Map<String, Object>> cached = readCache(cacheKey);
        if (cached != null) {
            cacheHitCounter.increment();
            return cached;
        }
        cacheMissCounter.increment();

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
        List<Map<String, Object>> result = executeAndCollect(flux.toString());
        writeCache(cacheKey, result);
        return result;
    }

    /**
     * kxj: 按fingerprint查询同模式日志样本
     */
    public List<Map<String, Object>> findByFingerprint(Long appid, String fingerprint,
                                                        Instant startTime, Instant endTime, int limit) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return List.of();
        }
        String cacheKey = buildCacheKey("fingerprint", appid, fingerprint, null, startTime, endTime, limit);
        List<Map<String, Object>> cached = readCache(cacheKey);
        if (cached != null) {
            cacheHitCounter.increment();
            return cached;
        }
        cacheMissCounter.increment();

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
        List<Map<String, Object>> result = executeAndCollect(flux.toString());
        writeCache(cacheKey, result);
        return result;
    }

    /**
     * kxj: P0 无上下文查询 - 给定一条日志的时间戳,按 threadName/host/logger
     * 任一维度查 ±N 秒的相邻日志,用于"在 Kibana 里看上下文"那种体感
     * 默认不缓存(上下文查询 QPS 极低且时间窗口短,缓存价值低)
     */
    public List<Map<String, Object>> getContext(Long appid, Instant anchorTime,
                                                  String threadName, String host, String logger,
                                                  int secondsBefore, int secondsAfter, int limit) {
        if (anchorTime == null) {
            return List.of();
        }
        int sb = Math.max(0, secondsBefore);
        int sa = Math.max(0, secondsAfter);
        int safeLimit = limit > 0 && limit <= 1000 ? limit : 100;
        Instant from = anchorTime.minusSeconds(sb);
        Instant to = anchorTime.plusSeconds(sa);

        StringBuilder flux = new StringBuilder();
        flux.append("from(bucket: \"").append(logBucket).append("\")\n");
        flux.append("  |> range(start: ").append(from).append(", stop: ").append(to).append(")\n");
        flux.append("  |> filter(fn: (r) => r._measurement == \"app_log\")\n");
        if (appid != null) {
            flux.append("  |> filter(fn: (r) => r[\"appid\"] == \"").append(appid).append("\")\n");
        }
        boolean hasFilter = false;
        if (threadName != null && !threadName.isBlank()) {
            flux.append("  |> filter(fn: (r) => r[\"threadName\"] == \"").append(escape(threadName)).append("\")\n");
            hasFilter = true;
        }
        if (host != null && !host.isBlank()) {
            flux.append("  |> filter(fn: (r) => r[\"host\"] == \"").append(escape(host)).append("\")\n");
            hasFilter = true;
        }
        if (logger != null && !logger.isBlank()) {
            flux.append("  |> filter(fn: (r) => r[\"logger\"] == \"").append(escape(logger)).append("\")\n");
            hasFilter = true;
        }
        if (!hasFilter) {
            log.warn("[spring-watch: getContext 全部维度为空, 拒绝全表扫 - appid={}, anchorTime={}]",
                    appid, anchorTime);
            return List.of();
        }
        flux.append("  |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n");
        flux.append("  |> sort(columns: [\"_time\"], desc: false)\n");
        flux.append("  |> limit(n: ").append(safeLimit).append(")\n");

        log.info("[spring-watch: 日志上下文查询 - appid={}, anchor={}, ±{}/{}s, thread={}, host={}, logger={}, limit={}]",
                appid, anchorTime, sb, sa, threadName, host, logger, safeLimit);
        return executeAndCollect(flux.toString());
    }

    private List<Map<String, Object>> executeAndCollect(String flux) {
        queryCounter.increment();
        List<FluxTable> tables;
        try {
            tables = queryApi.query(flux, influxOrg);
        } catch (Exception e) {
            queryFailCounter.increment();
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

    private String buildCacheKey(String op, Long appid, String kw, String level,
                                  Instant from, Instant to, int limit) {
        String raw = op + "|" + appid + "|" + (kw == null ? "" : kw) + "|" +
                (level == null ? "" : level) + "|" + from + "|" + to + "|" + limit;
        return CACHE_PREFIX + sha1Hex(raw);
    }

    private List<Map<String, Object>> readCache(String key) {
        if (!cacheEnabled) return null;
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("[spring-watch: 读缓存失败 - key={}, error={}]", key, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, List<Map<String, Object>> result) {
        if (!cacheEnabled) return;
        try {
            String json = objectMapper.writeValueAsString(result);
            redis.opsForValue().set(key, json, Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.debug("[spring-watch: 写缓存失败 - key={}, error={}]", key, e.getMessage());
        }
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String h = Integer.toHexString(b & 0xff);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
