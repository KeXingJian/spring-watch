package com.springwatch.analysis;

import com.influxdb.client.QueryApi;
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
    private Counter queryFailCounter;
    private Timer errorRateTimer;

    @PostConstruct
    void initMetrics() {
        this.errorRateQueryCounter = Counter.builder("spring.watch.aggregator.log.error_rate.query")
                .description("错误率聚合查询次数")
                .register(meterRegistry);
        this.queryFailCounter = Counter.builder("spring.watch.aggregator.log.query_fail")
                .description("InfluxDB 查询失败次数")
                .register(meterRegistry);
        this.errorRateTimer = Timer.builder("spring.watch.aggregator.log.error_rate.latency")
                .description("错误率聚合查询耗时")
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
                  |> count(column: "_value")
                  |> keep(columns: ["_value", "level"])
                  |> group()
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
            log.warn("[spring-watch: LogAggregator errorRate查询失败 - appid={}, error={}]", appid, e.getMessage(), e);
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

    public record ErrorRateStats(long total, long error, long warn, double errorRate) {
    }

}
