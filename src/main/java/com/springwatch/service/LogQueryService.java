package com.springwatch.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.springwatch.model.entity.LogDedupCount;
import com.springwatch.repository.LogDedupCountRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogQueryService {

    private static final String MEASUREMENT = "app_log";

    private final QueryApi queryApi;
    private final MeterRegistry meterRegistry;
    private final LogDedupCountRepository dedupCountRepository;

    @Value("${influxdb.log-bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String influxOrg;

    private Counter searchCounter;
    private Counter patternCounter;
    private Counter levelsCounter;
    private Counter traceCounter;
    private Counter contextCounter;
    private Counter dedupTopCounter;
    private Counter queryFailCounter;
    private Timer searchTimer;
    private Timer patternTimer;
    private Timer levelsTimer;
    private Timer traceTimer;
    private Timer contextTimer;
    private Timer dedupTopTimer;

    @PostConstruct
    void init() {
        this.searchCounter = Counter.builder("spring.watch.log.query.search").register(meterRegistry);
        this.patternCounter = Counter.builder("spring.watch.log.query.patterns").register(meterRegistry);
        this.levelsCounter = Counter.builder("spring.watch.log.query.levels").register(meterRegistry);
        this.traceCounter = Counter.builder("spring.watch.log.query.trace").register(meterRegistry);
        this.contextCounter = Counter.builder("spring.watch.log.query.context").register(meterRegistry);
        this.dedupTopCounter = Counter.builder("spring.watch.log.query.dedup_top").register(meterRegistry);
        this.queryFailCounter = Counter.builder("spring.watch.log.query.fail").register(meterRegistry);
        this.searchTimer = Timer.builder("spring.watch.log.query.search.latency").register(meterRegistry);
        this.patternTimer = Timer.builder("spring.watch.log.query.patterns.latency").register(meterRegistry);
        this.levelsTimer = Timer.builder("spring.watch.log.query.levels.latency").register(meterRegistry);
        this.traceTimer = Timer.builder("spring.watch.log.query.trace.latency").register(meterRegistry);
        this.contextTimer = Timer.builder("spring.watch.log.query.context.latency").register(meterRegistry);
        this.dedupTopTimer = Timer.builder("spring.watch.log.query.dedup_top.latency").register(meterRegistry);
    }

    /**
     * 关键字检索 + 级别过滤(支持 time/level/logger/thread/host/traceId/fingerprint 过滤)
     * 返回 SearchResult {rows, total, page, pageSize},按时间倒序
     * 分两路并发查 InfluxDB:一路 count 拿 total,一路 pivot+sort+offset+limit 拿当页
     */
    public SearchResult search(long appid, String keyword, String level, String logger, String threadName,
                                String traceId, String fingerprint, Instant from, Instant to,
                                int page, int pageSize) {
        searchCounter.increment();
        long start = System.nanoTime();
        int safePage = Math.max(page, 1);
        int safeSize = pageSize <= 0 ? 20 : Math.min(pageSize, 200);
        int skip = (safePage - 1) * safeSize;
        try {
            // 公共 filter 段(level/logger/threadName)
            String commonFilter = buildFilter(appid, level, logger, threadName, null, null, null, null);
            // 关键:inKeyword-filter / inTrace-filter / inFp-filter 都在 parseRows 之后做内存过滤,
            // 所以 total 也得用同样的内存过滤逻辑;为了精确,total 由客户端传回的 row 数量得到。
            // 但是 count 必须用 Flux 的 count(),所以这里**只对 base 字段做 count**,
            // 然后把 keyword/traceId/fingerprint 的影响记在结果里返回给前端做"显示 N / 匹配 M"。
            // 见 SearchResult.totalBase 字段。
            String commonHead = "from(bucket: \"" + bucket + "\")\n"
                    + "  |> range(start: " + formatInstant(from) + ", stop: " + formatInstant(to) + ")\n"
                    + commonFilter;

            // count 查询:只数 _field == "message" 的 record 数 = 日志条数(每条日志恰好 1 条 message field)
            // 否则会算上 level/logger/threadName/throwable/...所有 field,total 远大于真实日志数,
            // 导致前端分页器 totalPages 虚高。
            String countFlux = commonHead
                    + "\n  |> filter(fn: (r) => r._field == \"message\")"
                    + "\n  |> count()";
            // 分页查询:pivot 后必须 |> group() 合并所有 table,否则 limit 是 per-table 限
            // (数据按 host/logger/level/threadName/fingerprint 等 tag 分到 N 个 FluxTable,每个表都限 20,
            //  N=几十时 rows 就几百~上千,完全失控)
            String pageFlux = commonHead
                    + "\n  |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")"
                    + "\n  |> group()"
                    + "\n  |> sort(columns: [\"_time\"], desc: true)"
                    + "\n  |> limit(n: " + safeSize + ", offset: " + skip + ")";

            // 并发查
            List<FluxTable> countTables = queryApi.query(countFlux, influxOrg);
            List<FluxTable> pageTables = queryApi.query(pageFlux, influxOrg);

            long totalBase = 0L;
            for (FluxTable t : countTables) {
                for (FluxRecord r : t.getRecords()) {
                    Object v = r.getValue();
                    if (v instanceof Number n) totalBase = Math.max(totalBase, n.longValue());
                }
            }

            List<LogRow> rows = parseRows(pageTables, keyword);
            // keyword/traceId/fingerprint 是 in-memory 过滤,page 数据上要做,
            // 真实"匹配数" = totalBase 但经过 keyword/trace/fp 过滤后可能更少。
            // 这里我们返回 totalBase,前端基于 rows.length 显示"页内 X 条",并在 header 提示"共 Y 条(基础 count)"。
            if (traceId != null && !traceId.isBlank()) {
                rows.removeIf(row -> !traceId.equals(row.traceId));
            }
            if (fingerprint != null && !fingerprint.isBlank()) {
                rows.removeIf(row -> !fingerprint.equals(row.fingerprint));
            }
            return new SearchResult(rows, totalBase, safePage, safeSize, null);
        } catch (Exception e) {
            queryFailCounter.increment();
            log.warn("[spring-watch: log search失败 - appid={}, keyword={}, error={}]", appid, keyword, e.getMessage());
            return new SearchResult(List.of(), 0L, safePage, safeSize, e.getMessage());
        } finally {
            searchTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 级别分布(分页用)
     */
    public List<LevelCount> levelDistribution(long appid, Instant from, Instant to) {
        levelsCounter.increment();
        long start = System.nanoTime();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("from(bucket: \"").append(bucket).append("\")\n");
            sb.append("  |> range(start: ").append(formatInstant(from)).append(", stop: ").append(formatInstant(to)).append(")\n");
            sb.append(buildFilter(appid, null, null, null, null, null, null, "message")).append("\n");
            sb.append("  |> keep(columns: [\"level\", \"_value\"])\n");
            sb.append("  |> group(columns: [\"level\"])\n");
            sb.append("  |> count(column: \"_value\")\n");
            sb.append("  |> group()\n");
            List<FluxTable> tables = queryApi.query(sb.toString(), influxOrg);
            List<LevelCount> out = new ArrayList<>();
            for (FluxTable table : tables) {
                String level = null;
                long cnt = 0;
                for (FluxRecord r : table.getRecords()) {
                    Object l = r.getValueByKey("level");
                    if (l != null) level = l.toString();
                    Object v = r.getValue();
                    if (v instanceof Number n) cnt = n.longValue();
                }
                out.add(new LevelCount(level == null ? "UNKNOWN" : level, cnt));
            }
            return out;
        } catch (Exception e) {
            queryFailCounter.increment();
            log.warn("[spring-watch: log levelDistribution失败 - appid={}, error={}]", appid, e.getMessage(), e);
            return List.of();
        } finally {
            levelsTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 错误率时序:按 every 窗口统计 total / error / warn
     */
    public List<ErrorRateBucket> errorRateSeries(long appid, Instant from, Instant to, String every) {
        String window = (every == null || every.isBlank()) ? "1m" : every;
        long start = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        sb.append("from(bucket: \"").append(bucket).append("\")\n");
        sb.append("  |> range(start: ").append(formatInstant(from)).append(", stop: ").append(formatInstant(to)).append(")\n");
        sb.append(buildFilter(appid, null, null, null, null, null, null, "message")).append("\n");
        sb.append("  |> group(columns: [\"level\"])\n");
        sb.append("  |> aggregateWindow(every: ").append(window).append(", fn: count, createEmpty: false)\n");
        sb.append("  |> keep(columns: [\"_time\", \"_value\", \"level\"])\n");
        sb.append("  |> group()\n");
        sb.append("  |> sort(columns: [\"_time\"], desc: false)\n");
        Map<String, ErrorRateBucket> buckets = new LinkedHashMap<>();
        try {
            List<FluxTable> tables = queryApi.query(sb.toString(), influxOrg);
            for (FluxTable table : tables) {
                for (FluxRecord r : table.getRecords()) {
                    Object tObj = r.getValueByKey("_time");
                    if (tObj == null) continue;
                    String timeKey = tObj.toString();
                    String level = String.valueOf(r.getValueByKey("level"));
                    long cnt = r.getValue() instanceof Number n ? n.longValue() : 0;
                    ErrorRateBucket b = buckets.computeIfAbsent(timeKey, k -> new ErrorRateBucket(k, 0, 0, 0));
                    if ("ERROR".equalsIgnoreCase(level)) b.error += cnt;
                    else if ("WARN".equalsIgnoreCase(level)) b.warn += cnt;
                    else b.total += cnt;
                }
            }
        } catch (Exception e) {
            log.warn("[spring-watch: log errorRateSeries失败 - appid={}, error={}]", appid, e.getMessage(), e);
        }
        List<ErrorRateBucket> out = new ArrayList<>(buckets.values());
        for (ErrorRateBucket b : out) b.total = b.total + b.error + b.warn;
        return out;
    }

    /**
     * 模式聚类 TopN(按 fingerprint 聚合)
     */
    public List<PatternTop> topFingerprints(long appid, Instant from, Instant to, int topN, String level) {
        patternCounter.increment();
        long start = System.nanoTime();
        int safeTop = topN <= 0 ? 10 : topN;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("from(bucket: \"").append(bucket).append("\")\n");
            sb.append("  |> range(start: ").append(formatInstant(from)).append(", stop: ").append(formatInstant(to)).append(")\n");
            sb.append(buildFilter(appid, level, null, null, null, null, null, "message")).append("\n");
            sb.append("  |> group(columns: [\"fingerprint\"])\n");
            sb.append("  |> count()\n");
            sb.append("  |> sort(columns: [\"_value\"], desc: true)\n");
            sb.append("  |> limit(n: ").append(safeTop).append(")\n");

            List<FluxTable> tables = queryApi.query(sb.toString(), influxOrg);
            List<PatternTop> out = new ArrayList<>();
            for (FluxTable table : tables) {
                for (FluxRecord r : table.getRecords()) {
                    String fp = strVal(r.getValueByKey("fingerprint"));
                    long cnt = r.getValue() instanceof Number n ? n.longValue() : 0;
                    if (fp == null || "null".equals(fp) || "unknown".equals(fp)) continue;
                    out.add(new PatternTop(fp, null, cnt, 0L));
                }
            }
            // 合并 PG log_dedup_count 的去重计数,得到"真实总次数"
            // 注意:PG dedup_count 是近 1h 窗口的累加(Caffeine window + flush 周期),
            // 与 InfluxDB 查的 [from, to] 时间窗并不完全对齐,这里采用"任一指纹命中就补"
            if (!out.isEmpty()) {
                List<String> fps = out.stream().map(p -> p.fingerprint()).toList();
                List<LogDedupCountRepository.FpCount> rows = dedupCountRepository.sumDedupByFingerprints(appid, fps);
                Map<String, Long> dedupMap = new HashMap<>();
                for (LogDedupCountRepository.FpCount r : rows) {
                    if (r != null && r.getFp() != null) {
                        dedupMap.put(r.getFp(), r.getCnt() == null ? 0L : r.getCnt());
                    }
                }
                for (int i = 0; i < out.size(); i++) {
                    PatternTop p = out.get(i);
                    long dedup = dedupMap.getOrDefault(p.fingerprint(), 0L);
                    out.set(i, new PatternTop(p.fingerprint(), p.pattern(), p.count(), dedup));
                }
            }
            return out;
        } catch (Exception e) {
            queryFailCounter.increment();
            log.warn("[spring-watch: log topFingerprints失败 - appid={}, error={}]", appid, e.getMessage());
            return List.of();
        } finally {
            patternTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 单个 fingerprint 详情(模式名 + 最近一条样本)
     */
    public FingerprintDetail fingerprintDetail(long appid, String fingerprint, Instant from, Instant to) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("from(bucket: \"").append(bucket).append("\")\n");
            sb.append("  |> range(start: ").append(formatInstant(from)).append(", stop: ").append(formatInstant(to)).append(")\n");
            sb.append(buildFilter(appid, null, null, null, fingerprint, null, null, null)).append("\n");
            sb.append("  |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n");
            sb.append("  |> sort(columns: [\"_time\"], desc: true)\n");
            sb.append("  |> limit(n: 1)\n");
            List<FluxTable> tables = queryApi.query(sb.toString(), influxOrg);
            for (FluxTable table : tables) {
                for (FluxRecord r : table.getRecords()) {
                    return new FingerprintDetail(
                            fingerprint,
                            strVal(r.getValueByKey("pattern")),
                            strVal(r.getValueByKey("message")),
                            strVal(r.getValueByKey("throwable")),
                            strVal(r.getValueByKey("logger")),
                            strVal(r.getValueByKey("level")),
                            strVal(r.getValueByKey("traceId")),
                            r.getTime() == null ? null : r.getTime().toString()
                    );
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[spring-watch: log fingerprintDetail失败 - appid={}, fp={}, error={}]", appid, fingerprint, e.getMessage());
            return null;
        }
    }

    /**
     * 按 traceId 反查
     */
    public List<LogRow> byTraceId(String traceId, Long appid) {
        traceCounter.increment();
        long start = System.nanoTime();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("from(bucket: \"").append(bucket).append("\")\n");
            sb.append("  |> range(start: -24h)\n");
            sb.append(buildFilter(appid, null, null, null, null, traceId, null, "message")).append("\n");
            sb.append("  |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n");
            sb.append("  |> sort(columns: [\"_time\"])\n");
            sb.append("  |> limit(n: 200)\n");

            List<FluxTable> tables = queryApi.query(sb.toString(), influxOrg);
            return parsePivotedRows(tables);
        } catch (Exception e) {
            queryFailCounter.increment();
            log.warn("[spring-watch: log byTraceId失败 - traceId={}, error={}]", traceId, e.getMessage());
            return List.of();
        } finally {
            traceTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 上下文:基于选中行时间戳 ± before/after 秒,按 thread/logger/host 过滤
     */
    public List<LogRow> context(long appid, Instant center, String threadName, String logger, String host,
                                 int beforeSec, int afterSec, int limit) {
        contextCounter.increment();
        long start = System.nanoTime();
        try {
            Instant from = center.minusSeconds(beforeSec <= 0 ? 30 : beforeSec);
            Instant to = center.plusSeconds(afterSec <= 0 ? 30 : afterSec);
            StringBuilder sb = new StringBuilder();
            sb.append("from(bucket: \"").append(bucket).append("\")\n");
            sb.append("  |> range(start: ").append(formatInstant(from)).append(", stop: ").append(formatInstant(to)).append(")\n");
            sb.append(buildFilter(appid, null, logger, threadName, null, null, host, "message")).append("\n");
            sb.append("  |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n");
            sb.append("  |> sort(columns: [\"_time\"])\n");
            sb.append("  |> limit(n: ").append(limit <= 0 ? 100 : limit).append(")\n");

            List<FluxTable> tables = queryApi.query(sb.toString(), influxOrg);
            return parsePivotedRows(tables);
        } catch (Exception e) {
            queryFailCounter.increment();
            log.warn("[spring-watch: log context失败 - appid={}, error={}]", appid, e.getMessage());
            return List.of();
        } finally {
            contextTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 高频去重模式(去重计数 TopN) - 来自 PG log_dedup_count 表,InfluxDB 仅补 pattern/level 元数据
     */
    public List<DedupTop> topDedup(long appid, int topN) {
        dedupTopCounter.increment();
        long start = System.nanoTime();
        int safeTop = topN <= 0 ? 10 : topN;
        try {
            List<LogDedupCount> rows = dedupCountRepository.findTopByAppidOrderByCount(
                    appid, PageRequest.of(0, safeTop));
            if (rows.isEmpty()) {
                return List.of();
            }
            List<String> fps = rows.stream().map(LogDedupCount::getFingerprint).toList();
            Map<String, DedupMeta> meta = fetchMetaFromInflux(appid, fps);
            List<DedupTop> out = new ArrayList<>(rows.size());
            for (LogDedupCount r : rows) {
                DedupMeta m = meta.getOrDefault(r.getFingerprint(), DedupMeta.EMPTY);
                out.add(new DedupTop(r.getFingerprint(), m.logger, r.getDedupCount(), m.pattern, m.level));
            }
            return out;
        } catch (Exception e) {
            queryFailCounter.increment();
            log.warn("[spring-watch: log topDedup失败 - appid={}, error={}]", appid, e.getMessage());
            return List.of();
        } finally {
            dedupTopTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private Map<String, DedupMeta> fetchMetaFromInflux(long appid, List<String> fingerprints) {
        Map<String, DedupMeta> out = new HashMap<>();
        if (fingerprints.isEmpty()) {
            return out;
        }
        StringBuilder orClause = new StringBuilder();
        for (int i = 0; i < fingerprints.size(); i++) {
            if (i > 0) orClause.append(" or ");
            orClause.append("r.fingerprint == \"").append(escape(fingerprints.get(i))).append("\"");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("from(bucket: \"").append(bucket).append("\")\n");
        sb.append("  |> range(start: -24h)\n");
        sb.append(buildFilter(appid, null, null, null, null, null, null, "message")).append("\n");
        sb.append("  |> filter(fn: (r) => ").append(orClause).append(")\n");
        sb.append("  |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n");
        sb.append("  |> group(columns: [\"fingerprint\"])\n");
        sb.append("  |> last()\n");
        try {
            List<FluxTable> tables = queryApi.query(sb.toString(), influxOrg);
            for (FluxTable t : tables) {
                for (FluxRecord r : t.getRecords()) {
                    String fp = strVal(r.getValueByKey("fingerprint"));
                    if (fp == null || "unknown".equals(fp)) continue;
                    if (!fingerprints.contains(fp)) continue;
                    out.put(fp, new DedupMeta(
                            strVal(r.getValueByKey("pattern")),
                            strVal(r.getValueByKey("level")),
                            strVal(r.getValueByKey("logger"))));
                }
            }
        } catch (Exception e) {
            log.warn("[spring-watch: log topDedup元数据补充失败 - appid={}, error={}]", appid, e.getMessage());
        }
        return out;
    }

    private record DedupMeta(String pattern, String level, String logger) {
        static final DedupMeta EMPTY = new DedupMeta(null, null, null);
    }

    /* ============================ helpers ============================ */

    /**
     * 拼装 Flux filter 管道(带 2 空格缩进,含首尾 `|> filter(fn: (r) => ...)`).
     * 任意参数为 null/blank 时跳过该条件,避免产生恒真条件,统一由 helper 负责闭合括号,杜绝漏写 `)`。
     */
    private String buildFilter(Long appid, String level, String logger, String threadName,
                                String fingerprint, String traceId, String host, String field) {
        StringBuilder sb = new StringBuilder();
        sb.append("  |> filter(fn: (r) => r._measurement == \"").append(MEASUREMENT).append("\"");
        appendEq(sb, "r.appid", appid == null ? null : appid.toString());
        appendEq(sb, "r._field", field);
        appendEq(sb, "r.level", level);
        appendEq(sb, "r.logger", logger);
        appendEq(sb, "r.threadName", threadName);
        appendEq(sb, "r.fingerprint", fingerprint);
        appendEq(sb, "r.traceId", traceId);
        appendEq(sb, "r.host", host);
        sb.append(")");
        return sb.toString();
    }

    private static void appendEq(StringBuilder sb, String field, String value) {
        if (value == null || value.isBlank()) return;
        sb.append(" and ").append(field).append(" == \"").append(escape(value)).append("\"");
    }

    private List<LogRow> parseRows(List<FluxTable> tables, String keyword) {
        Map<String, LogRow> byTime = new LinkedHashMap<>();
        for (FluxTable table : tables) {
            for (FluxRecord r : table.getRecords()) {
                Object tObj = r.getValueByKey("_time");
                if (tObj == null) continue;
                String timeKey = tObj.toString();
                LogRow row = byTime.computeIfAbsent(timeKey, k -> new LogRow());
                row.time = timeKey;
                if (row.appid == null) row.appid = strVal(r.getValueByKey("appid"));
                if (row.level == null) row.level = strVal(r.getValueByKey("level"));
                if (row.logger == null) row.logger = strVal(r.getValueByKey("logger"));
                if (row.threadName == null) row.threadName = strVal(r.getValueByKey("threadName"));
                if (row.fingerprint == null) row.fingerprint = strVal(r.getValueByKey("fingerprint"));
                if (row.host == null) row.host = strVal(r.getValueByKey("host"));
                if (row.service == null) row.service = strVal(r.getValueByKey("service"));
                if (row.method == null) row.method = strVal(r.getValueByKey("method"));
                if (row.env == null) row.env = strVal(r.getValueByKey("env"));
                if (row.pattern == null) row.pattern = strVal(r.getValueByKey("pattern"));
                if (row.traceId == null) row.traceId = strVal(r.getValueByKey("traceId"));
                String msg = strVal(r.getValueByKey("message"));
                if (msg != null) row.message = msg;
                String th = strVal(r.getValueByKey("throwable"));
                if (th != null) row.throwable = th;
            }
        }
        List<LogRow> out = new ArrayList<>(byTime.values());
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.toLowerCase();
            out.removeIf(row -> {
                String m = row.message == null ? "" : row.message.toLowerCase();
                String th = row.throwable == null ? "" : row.throwable.toLowerCase();
                return !(m.contains(kw) || th.contains(kw));
            });
        }
        return out;
    }

    private List<LogRow> parsePivotedRows(List<FluxTable> tables) {
        List<LogRow> out = new ArrayList<>();
        for (FluxTable table : tables) {
            for (FluxRecord r : table.getRecords()) {
                LogRow row = new LogRow();
                Object tObj = r.getValueByKey("_time");
                row.time = tObj == null ? null : tObj.toString();
                row.appid = strVal(r.getValueByKey("appid"));
                row.level = strVal(r.getValueByKey("level"));
                row.logger = strVal(r.getValueByKey("logger"));
                row.threadName = strVal(r.getValueByKey("threadName"));
                row.fingerprint = strVal(r.getValueByKey("fingerprint"));
                row.host = strVal(r.getValueByKey("host"));
                row.service = strVal(r.getValueByKey("service"));
                row.method = strVal(r.getValueByKey("method"));
                row.env = strVal(r.getValueByKey("env"));
                row.pattern = strVal(r.getValueByKey("pattern"));
                row.traceId = strVal(r.getValueByKey("traceId"));
                row.message = strVal(r.getValueByKey("message"));
                row.throwable = strVal(r.getValueByKey("throwable"));
                out.add(row);
            }
        }
        return out;
    }

    private static String strVal(Object o) {
        if (o == null) return null;
        return o.toString();
    }

    private static String formatInstant(Instant t) {
        return "time(v: " + t.toString() + ")";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static class LogRow {
        public String time;
        public String appid;
        public String level;
        public String logger;
        public String threadName;
        public String fingerprint;
        public String host;
        public String service;
        public String method;
        public String env;
        public String pattern;
        public String traceId;
        public String message;
        public String throwable;
    }

    public static class ErrorRateBucket {
        public String time;
        public long total;
        public long error;
        public long warn;
        public ErrorRateBucket(String time, long total, long error, long warn) {
            this.time = time;
            this.total = total;
            this.error = error;
            this.warn = warn;
        }
    }

    public record LevelCount(String level, long count) {}

    public record PatternTop(String fingerprint, String pattern, long count, long dedupCount) {}

    public record FingerprintDetail(String fingerprint, String pattern, String message, String throwable,
                                    String logger, String level, String traceId, String lastSeen) {}

    public record DedupTop(String fingerprint, String logger, long dedupCount, String pattern, String level) {}

    /**
     * search 分页结果。total 是 base count(还没在内存里过 keyword/traceId/fingerprint 过滤的条数),
     * rows 是过完所有过滤后的当页数据。当 keyword/trace/fp 命中 0 条但 base > 0 时,total > 0 但 rows 为空。
     * error 非空表示查询失败(rows/total 无意义),前端应在空态里展示原因,避免出现"静默 0 条"。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchResult(List<LogRow> rows, long total, int page, int pageSize, String error) {
        public int totalPages() {
            if (pageSize <= 0 || total <= 0) return 0;
            return (int) ((total + pageSize - 1) / pageSize);
        }
    }
}
