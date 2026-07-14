package com.springwatch.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteOptions;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.WriteParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Slf4j
@Configuration
public class InfluxDBConfig {

    @Value("${influxdb.url}")
    private String url;

    @Value("${influxdb.token}")
    private String token;

    @Value("${influxdb.org}")
    private String influxOrg;

    @Value("${influxdb.metrics-bucket}")
    private String metricsBucket;

    @Value("${influxdb.log-bucket}")
    private String logBucket;

    @Value("${influxdb.self-metrics-bucket:self_metrics}")
    private String selfMetricsBucket;

    @Value("${influxdb.write.retry-interval-ms:1000}")
    private int writeRetryIntervalMs;

    @Value("${influxdb.write.max-retries:3}")
    private int writeMaxRetries;

    @Value("${influxdb.write.max-retry-delay-ms:5000}")
    private int writeMaxRetryDelayMs;

    @Value("${influxdb.write.max-retry-time-ms:60000}")
    private int writeMaxRetryTimeMs;

    @Value("${influxdb.write.jitter-interval-ms:200}")
    private int writeJitterIntervalMs;

    @Bean(destroyMethod = "close")
    public InfluxDBClient influxDBClient() {
        log.info("[kxj: InfluxDBClient 初始化 - url={}, org={}]",
                url, influxOrg);
        return InfluxDBClientFactory.create(url, token.toCharArray(), influxOrg);
    }

    @Bean
    public WriteParameters metricsWriteParameters() {
        return new WriteParameters(metricsBucket, influxOrg, WritePrecision.NS);
    }

    @Bean
    public WriteParameters logWriteParameters() {
        return new WriteParameters(logBucket, influxOrg, WritePrecision.NS);
    }

    @Bean
    public WriteParameters selfMetricsWriteParameters() {
        return new WriteParameters(selfMetricsBucket, influxOrg, WritePrecision.NS);
    }

    // ============================================================
    //  M-WriteApiSplit: 拆 4 个独立 WriteApi Bean,各桶独立 batch/flush/buffer
    //  原因(白皮书 0.5):原 1 个共享 WriteApi,3 个 topic 互相抢 buffer,
    //  metrics flush 卡住时 logs 也跟着卡。拆分后互不干扰。
    //
    //  4 个桶独立调参:
    //    metrics  (大流量,batch=5000, flush=1000ms, buffer=100000)
    //    logs     (中等流量,batch=3000, flush=1000ms, buffer=50000)
    //    self     (低频,默认值即可)
    //    infra    (低频,默认值即可,5 个 monitor 共享)
    //
    //  注入方式:用 @Qualifier("metricsWriteApi") 等,见各 Consumer/Collector。
    // ============================================================

    @Bean(name = "metricsWriteApi", destroyMethod = "close")
    public WriteApi metricsWriteApi(InfluxDBClient client) {
        WriteOptions options = WriteOptions.builder()
                .batchSize(5000)
                .flushInterval(1000)
                .bufferLimit(100000)
                .retryInterval(writeRetryIntervalMs)
                .maxRetries(writeMaxRetries)
                .maxRetryDelay(writeMaxRetryDelayMs)
                .maxRetryTime(writeMaxRetryTimeMs)
                .jitterInterval(writeJitterIntervalMs)
                .build();
        log.info("[kxj: WriteApi(metrics) 初始化 - batch=5000, flush=1000ms, buffer=100000]");
        return client.makeWriteApi(options);
    }

    @Bean(name = "logsWriteApi", destroyMethod = "close")
    public WriteApi logsWriteApi(InfluxDBClient client) {
        WriteOptions options = WriteOptions.builder()
                .batchSize(3000)
                .flushInterval(1000)
                .bufferLimit(50000)
                .retryInterval(writeRetryIntervalMs)
                .maxRetries(writeMaxRetries)
                .maxRetryDelay(writeMaxRetryDelayMs)
                .maxRetryTime(writeMaxRetryTimeMs)
                .jitterInterval(writeJitterIntervalMs)
                .build();
        log.info("[kxj: WriteApi(logs) 初始化 - batch=3000, flush=1000ms, buffer=50000]");
        return client.makeWriteApi(options);
    }

    @Bean(name = "selfMetricsWriteApi", destroyMethod = "close")
    public WriteApi selfMetricsWriteApi(InfluxDBClient client) {
        WriteOptions options = WriteOptions.builder()
                .batchSize(1000)
                .flushInterval(2000)
                .bufferLimit(20000)
                .retryInterval(writeRetryIntervalMs)
                .maxRetries(writeMaxRetries)
                .maxRetryDelay(writeMaxRetryDelayMs)
                .maxRetryTime(writeMaxRetryTimeMs)
                .jitterInterval(writeJitterIntervalMs)
                .build();
        log.info("[kxj: WriteApi(selfMetrics) 初始化 - batch=1000, flush=2000ms, buffer=20000]");
        return client.makeWriteApi(options);
    }

    @Bean(name = "infraWriteApi", destroyMethod = "close")
    public WriteApi infraWriteApi(InfluxDBClient client) {
        WriteOptions options = WriteOptions.builder()
                .batchSize(1000)
                .flushInterval(2000)
                .bufferLimit(20000)
                .retryInterval(writeRetryIntervalMs)
                .maxRetries(writeMaxRetries)
                .maxRetryDelay(writeMaxRetryDelayMs)
                .maxRetryTime(writeMaxRetryTimeMs)
                .jitterInterval(writeJitterIntervalMs)
                .build();
        log.info("[kxj: WriteApi(infra) 初始化 - batch=1000, flush=2000ms, buffer=20000]");
        return client.makeWriteApi(options);
    }

    /**
     * kxj: 共享 QueryApi 池 - InfluxDBClient.getQueryApi() 内部基于 OkHttp/Retrofit 共享连接池,
     * 反复 getQueryApi() 仅新建轻量包装,但统一单例更利于连接复用与监控埋点
     */
    @Bean
    public QueryApi queryApi(InfluxDBClient client) {
        log.info("[kxj: QueryApi 初始化(单例共享) - org={}]", influxOrg);
        return client.getQueryApi();
    }
}
