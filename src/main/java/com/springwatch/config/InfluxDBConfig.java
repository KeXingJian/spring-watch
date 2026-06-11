package com.springwatch.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteOptions;
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

    @Value("${influxdb.bucket}")
    private String bucket;

    @Bean
    public InfluxDBClient influxDBClient() {
        log.info("[spring-watch: InfluxDBClient 初始化 - url={}, org={}, bucket={}]", url, influxOrg, bucket);
        return InfluxDBClientFactory.create(url, token.toCharArray(), influxOrg, bucket);
    }

    @Bean
    public WriteApi writeApi(InfluxDBClient client) {
        WriteOptions options = WriteOptions.builder()
                .batchSize(500)
                .flushInterval(5000)
                .build();
        return client.makeWriteApi(options);
    }
}