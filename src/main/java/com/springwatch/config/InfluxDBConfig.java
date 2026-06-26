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

import java.time.Duration;

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

    @Value("${influxdb.write.batch-size:1000}")
    private int writeBatchSize;

    @Value("${influxdb.write.flush-interval-ms:1000}")
    private int writeFlushIntervalMs;

    @Value("${influxdb.write.buffer-limit:100000}")
    private int writeBufferLimit;

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
        log.info("[spring-watch: InfluxDBClient 初始化 - url={}, org={}]",
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

    @Bean(destroyMethod = "close")
    public WriteApi writeApi(InfluxDBClient client) {
        WriteOptions options = WriteOptions.builder()
                .batchSize(writeBatchSize)
                .flushInterval(writeFlushIntervalMs)
                .bufferLimit(writeBufferLimit)
                .retryInterval(writeRetryIntervalMs)
                .maxRetries(writeMaxRetries)
                .maxRetryDelay(writeMaxRetryDelayMs)
                .maxRetryTime(writeMaxRetryTimeMs)
                .jitterInterval(writeJitterIntervalMs)
                .build();
        log.info("[spring-watch: WriteApi 初始化 - batchSize={}, flushInterval={}ms, bufferLimit={}, maxRetries={}, maxRetryTime={}ms]",
                writeBatchSize, writeFlushIntervalMs, writeBufferLimit,
                writeMaxRetries, writeMaxRetryTimeMs);
        return client.makeWriteApi(options);
    }

    /**
     * kxj: 共享 QueryApi 池 - InfluxDBClient.getQueryApi() 内部基于 OkHttp/Retrofit 共享连接池,
     * 反复 getQueryApi() 仅新建轻量包装,但统一单例更利于连接复用与监控埋点
     */
    @Bean
    public QueryApi queryApi(InfluxDBClient client) {
        log.info("[spring-watch: QueryApi 初始化(单例共享) - org={}]", influxOrg);
        return client.getQueryApi();
    }
}
