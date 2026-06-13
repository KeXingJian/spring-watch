package com.springwatch.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.Bucket;
import com.influxdb.client.domain.Organization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InfluxDBBucketInitializer {

    private final InfluxDBClient influxDBClient;

    @Value("${influxdb.org}")
    private String influxOrg;

    @Value("${influxdb.metrics-bucket}")
    private String metricsBucket;

    @Value("${influxdb.log-bucket}")
    private String logBucket;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureLogBucket() {
        try {
            Organization org = influxDBClient.getOrganizationsApi().findOrganizations().stream()
                    .filter(o -> influxOrg.equals(o.getName()))
                    .findFirst()
                    .orElse(null);
            if (org == null) {
                log.error("[spring-watch: InfluxDB organization 不存在 - org={}]", influxOrg);
                return;
            }
            ensureBucket(metricsBucket, org);
            ensureBucket(logBucket, org);
        } catch (Exception e) {
            log.error("[spring-watch: InfluxDB bucket 初始化失败 - error={}]", e.getMessage(), e);
        }
    }

    private void ensureBucket(String bucketName, Organization org) {
        Bucket existing = influxDBClient.getBucketsApi().findBucketByName(bucketName);
        if (existing == null) {
            Bucket created = influxDBClient.getBucketsApi()
                    .createBucket(bucketName, org);
            log.info("[spring-watch: InfluxDB bucket 创建成功 - name={}, id={}]",
                    bucketName, created.getId());
        } else {
            log.info("[spring-watch: InfluxDB bucket 已存在 - name={}]", bucketName);
        }
    }
}
