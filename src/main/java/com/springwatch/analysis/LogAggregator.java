package com.springwatch.analysis;

import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogAggregator {

    private final QueryApi queryApi;
    private final MeterRegistry meterRegistry;

    @Value("${influxdb.log-bucket}")
    private String logBucket;

    @Value("${influxdb.org}")
    private String influxOrg;

    private Counter errorRateQueryCounter;
    private Counter topPatternsQueryCounter;
    private Counter errorRateSeriesQueryCounter;
    private Counter queryFailCounter;
    private Timer errorRateTimer;
    private Timer topPatternsTimer;
    private Timer errorRateSeriesTimer;

    @PostConstruct
    void initMetrics() {
        this.errorRateQueryCounter = Counter.builder("spring.watch.aggregator.log.error_rate.query")
                .description("错误率聚合查询次数")
                .register(meterRegistry);
        this.topPatternsQueryCounter = Counter.builder("spring.watch.aggregator.log.top_patterns.query")
                .description("TopN 模式查询次数")
                .register(meterRegistry);
        this.errorRateSeriesQueryCounter = Counter.builder("spring.watch.aggregator.log.error_rate_series.query")
                .description("错误率时序查询次数")
                .register(meterRegistry);
        this.queryFailCounter = Counter.builder("spring.watch.aggregator.log.query_fail")
                .description("InfluxDB 查询失败次数")
                .register(meterRegistry);
        this.errorRateTimer = Timer.builder("spring.watch.aggregator.log.error_rate.latency")
                .description("错误率聚合查询耗时")
                .register(meterRegistry);
        this.topPatternsTimer = Timer.builder("spring.watch.aggregator.log.top_patterns.latency")
                .description("TopN 模式查询耗时")
                .register(meterRegistry);
        this.errorRateSeriesTimer = Timer.builder("spring.watch.aggregator.log.error_rate_series.latency")
                .description("错误率时序查询耗时")
                .register(meterRegistry);
    }

    /**
     * kxj: 错误率聚合-某appid指定窗口内的total/error/warn计数
     */
    public ErrorRateStats errorRate(long appid, Instant from, Instant to) {
        log.debug("[spring-watch: LogAggregator errorRate 查询开始 - appid={}, from={}, to={}]", appid, from, to);
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "app_log")
                  |> filter(fn: (r) => r.appid == "%d")
                  |> filter(fn: (r) => r._field == "message")
                  |> group(columns: ["level"])
                  |> count()
                """, logBucket, from, to, appid);

        AtomicLong total = new AtomicLong();
        AtomicLong error = new AtomicLong();
        AtomicLong warn = new AtomicLong();
        errorRateQueryCounter.increment();
        long start = System.nanoTime();
        try {
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            tables.stream()
                    .flatMap(t -> t.getRecords().stream())
                    .filter(r -> r.getValue() instanceof Number)
                    .forEach(r -> {
                        long cnt = ((Number) r.getValue()).longValue();
                        total.addAndGet(cnt);
                        Object levelObj = r.getValueByKey("level");
                        String level = levelObj == null ? "" : levelObj.toString();
                        if ("ERROR".equals(level)) {
                            error.addAndGet(cnt);
                        } else if ("WARN".equals(level)) {
                            warn.addAndGet(cnt);
                        }
                    });
        } catch (Exception e) {
            queryFailCounter.increment();
            log.warn("[spring-watch: LogAggregator errorRate查询失败 - appid={}, error={}]", appid, e.getMessage());
        } finally {
            errorRateTimer.record(Duration.ofNanos(System.nanoTime() - start));
        }
        long totalVal = total.get();
        long errorVal = error.get();
        long warnVal = warn.get();
        double rate = totalVal == 0 ? 0.0 : (double) errorVal / totalVal;
        log.debug("[spring-watch: LogAggregator errorRate - appid={}, total={}, error={}, warn={}, rate={}]",
                appid, totalVal, errorVal, warnVal, rate);
        return new ErrorRateStats(totalVal, errorVal, warnVal, rate);
    }

    /**
     * kxj: TopN异常模式-按fingerprint聚合ERROR级别日志,取数量最多的topN
     */
    public List<PatternStats> topPatterns(long appid, Instant from, Instant to, int topN) {
        log.debug("[spring-watch: LogAggregator topPatterns 查询开始 - appid={}, from={}, to={}, topN={}]", appid, from, to, topN);
        int safeTop = topN <= 0 ? 10 : topN;
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "app_log")
                  |> filter(fn: (r) => r.appid == "%d")
                  |> filter(fn: (r) => r.level == "ERROR")
                  |> filter(fn: (r) => r._field == "message")
                  |> group(columns: ["fingerprint"])
                  |> count()
                  |> sort(columns: ["_value"], desc: true)
                  |> limit(n: %d)
                """, logBucket, from, to, appid, safeTop);

        List<PatternStats> result = new ArrayList<>();
        topPatternsQueryCounter.increment();
        long start = System.nanoTime();
        try {
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            tables.stream()
                    .flatMap(t -> t.getRecords().stream())
                    .filter(r -> r.getValue() instanceof Number)
                    .map(r -> {
                        long count = ((Number) r.getValue()).longValue();
                        Object fp = r.getValueByKey("fingerprint");
                        return new PatternStats(
                                fp == null ? "unknown" : fp.toString(),
                                null,
                                count);
                    })
                    .forEach(result::add);
        } catch (Exception e) {
            queryFailCounter.increment();
            log.warn("[spring-watch: LogAggregator topPatterns查询失败 - appid={}, error={}]",
                    appid, e.getMessage());
        } finally {
            topPatternsTimer.record(Duration.ofNanos(System.nanoTime() - start));
        }
        log.debug("[spring-watch: LogAggregator topPatterns - appid={}, topN={}, returned={}]",
                appid, safeTop, result.size());
        return result;
    }

    /**
     * kxj: 错误率时间序列-用于趋势曲线
     */
    public List<ErrorRatePoint> errorRateSeries(long appid, Instant from, Instant to, String every) {
        String window = (every == null || every.isBlank()) ? "1m" : every;
        log.debug("[spring-watch: LogAggregator errorRateSeries 查询开始 - appid={}, from={}, to={}, window={}]", appid, from, to, window);
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "app_log")
                  |> filter(fn: (r) => r.appid == "%d")
                  |> filter(fn: (r) => r._field == "message")
                  |> aggregateWindow(every: %s, fn: count, createEmpty: true)
                  |> group(columns: ["_time", "level"])
                """, logBucket, from, to, appid, window);

        List<ErrorRatePoint> series = new ArrayList<>();
        errorRateSeriesQueryCounter.increment();
        long start = System.nanoTime();
        try {
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            tables.stream()
                    .flatMap(t -> t.getRecords().stream())
                    .map(r -> {
                        Object value = r.getValue();
                        long cnt = value instanceof Number ? ((Number) value).longValue() : 0L;
                        Object levelObj = r.getValueByKey("level");
                        String level = levelObj == null ? "" : levelObj.toString();
                        return new ErrorRatePoint(r.getTime(), level, cnt);
                    })
                    .forEach(series::add);
        } catch (Exception e) {
            queryFailCounter.increment();
            log.warn("[spring-watch: LogAggregator errorRateSeries查询失败 - appid={}, error={}]", appid, e.getMessage());
        } finally {
            errorRateSeriesTimer.record(Duration.ofNanos(System.nanoTime() - start));
        }
        log.debug("[spring-watch: LogAggregator errorRateSeries - appid={}, points={}]", appid, series.size());
        return series;
    }

    public record ErrorRateStats(long total, long error, long warn, double errorRate) {
    }

    public record PatternStats(String fingerprint, String pattern, long count) {
    }

    public record ErrorRatePoint(Instant time, String level, long count) {
    }
}
