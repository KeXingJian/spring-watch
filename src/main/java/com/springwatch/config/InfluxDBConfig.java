package com.springwatch.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
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

    @Bean
    public InfluxDBClient influxDBClient() {
        log.info("[spring-watch: InfluxDBClient 初始化 - url={}, org={}]",
                url, influxOrg);
        return InfluxDBClientFactory.create(url, token.toCharArray(), influxOrg);
    }

    @Bean
    public WriteParameters metricsWriteParameters(){
        return new WriteParameters(metricsBucket,influxOrg, WritePrecision.NS);
    }

    @Bean
    public WriteParameters logWriteParameters(){
        return new WriteParameters(logBucket,influxOrg, WritePrecision.NS);
    }

    @Bean(destroyMethod = "close")
    public WriteApi writeApi(InfluxDBClient client) {
        WriteOptions options = WriteOptions.builder()
                .batchSize(500)
                .flushInterval(5000)
                .build();
        return client.makeWriteApi(options);
    }
}
