package com.springwatch.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.Bucket;
import com.influxdb.client.domain.BucketRetentionRules;
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
public class InfraMetricsBucketInitializer {

    private final InfluxDBClient influxDBClient;

    @Value("${influxdb.org}")
    private String influxOrg;

    @Value("${influxdb.infra-bucket:infra_metrics}")
    private String infraBucket;

    @Value("${influxdb.retention.infra-seconds:604800}")
    private int infraRetentionSeconds;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        try {
            Organization org = influxDBClient.getOrganizationsApi().findOrganizations().stream()
                    .filter(o -> influxOrg.equals(o.getName()))
                    .findFirst()
                    .orElse(null);
            if (org == null) {
                log.error("[spring-watch: InfluxDB organization 不存在 - org={}]", influxOrg);
                return;
            }
            ensureBucketInternal(infraBucket, org, infraRetentionSeconds);
        } catch (Exception e) {
            log.error("[spring-watch: infra_metrics bucket 初始化失败 - error={}]", e.getMessage(), e);
        }
    }

    private void ensureBucketInternal(String bucketName, Organization org, int retentionSeconds) {
        Bucket existing = influxDBClient.getBucketsApi().findBucketByName(bucketName);
        if (existing == null) {
            BucketRetentionRules rules = new BucketRetentionRules().everySeconds(retentionSeconds);
            Bucket created = influxDBClient.getBucketsApi().createBucket(bucketName, rules, org);
            log.info("[spring-watch: infra bucket 创建成功 - name={}, id={}, retentionSeconds={}]",
                    bucketName, created.getId(), retentionSeconds);
        } else {
            Integer existEvery = null;
            if (existing.getRetentionRules() != null && !existing.getRetentionRules().isEmpty()) {
                existEvery = existing.getRetentionRules().get(0).getEverySeconds();
            }
            if (existEvery == null || existEvery != retentionSeconds) {
                existing.getRetentionRules().clear();
                existing.getRetentionRules().add(new BucketRetentionRules().everySeconds(retentionSeconds));
                influxDBClient.getBucketsApi().updateBucket(existing);
                log.info("[spring-watch: infra bucket retention 更新 - name={}, retentionSeconds={}->{}]",
                        bucketName, existEvery, retentionSeconds);
            } else {
                log.info("[spring-watch: infra bucket 已存在 - name={}, retentionSeconds={}]",
                        bucketName, existEvery);
            }
        }
    }
}
