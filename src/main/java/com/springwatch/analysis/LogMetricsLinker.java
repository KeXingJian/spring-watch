package com.springwatch.analysis;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogMetricsLinker {

    private final LogAggregator aggregator;
    private final InfluxDBClient influxDBClient;

    @Value("${influxdb.metrics-bucket}")
    private String metricsBucket;

    @Value("${influxdb.org}")
    private String influxOrg;

    @Value("${spring-watch.log.linker.default-metric:jvm_memory_used_bytes}")
    private String defaultMetric;

    /**
     * kxj: 指标-日志关联-计算指定窗口内日志错误率与指标均值的综合关联分数
     */
    public CorrelationReport correlate(long appid, Instant from, Instant to, String metricName) {
        String target = metricName == null || metricName.isBlank() ? defaultMetric : metricName;
        log.debug("[spring-watch: LogMetricsLinker correlate 开始 - appid={}, from={}, to={}, metric={}", appid, from, to, target);
        LogAggregator.ErrorRateStats logStats = aggregator.errorRate(appid, from, to);
        double metricAvg = queryMetricMean(appid, target, from, to);
        double score = logStats.errorRate() * 0.6 + normalize(metricAvg) * 0.4;
        log.debug("[spring-watch: LogMetricsLinker 关联 - appid={}, metric={}, errorRate={}, metricAvg={}, score={}]",
                appid, target, logStats.errorRate(), metricAvg, score);
        return new CorrelationReport(appid, target, logStats.errorRate(), metricAvg, score);
    }

    private double queryMetricMean(long appid, String metricName, Instant from, Instant to) {
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "springboot_metrics")
                  |> filter(fn: (r) => r.appid == "%d")
                  |> filter(fn: (r) => r.metric == "%s")
                  |> filter(fn: (r) => r._field == "value")
                  |> mean()
                """, metricsBucket, from, to, appid, metricName);
        double result = 0.0;
        try {
            QueryApi queryApi = influxDBClient.getQueryApi();
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            Double found = tables.stream()
                    .flatMap(t -> t.getRecords().stream())
                    .filter(r -> r.getValue() instanceof Number)
                    .map(r -> ((Number) r.getValue()).doubleValue())
                    .findFirst()
                    .orElse(null);
            if (found != null) {
                result = found;
                log.debug("[spring-watch: LogMetricsLinker 指标均值 - appid={}, metric={}, mean={}", appid, metricName, result);
                return result;
            }
        } catch (Exception e) {
            log.warn("[spring-watch: LogMetricsLinker 指标均值查询失败 - appid={}, metric={}, error={}]",
                    appid, metricName, e.getMessage());
        }
        log.debug("[spring-watch: LogMetricsLinker 指标均值 - appid={}, metric={}, mean={}", appid, metricName, result);
        return result;
    }

    private double normalize(double v) {
        if (v <= 0) return 0.0;
        double normalized = v / 100.0;
        if (normalized > 1.0) {
            normalized = Math.log10(Math.max(v, 1.0)) / 10.0;
        }
        return Math.min(1.0, normalized);
    }

    public record CorrelationReport(long appid, String metricName, double errorRate,
                                    double metricMean, double score) {
    }
}
