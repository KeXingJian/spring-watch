package com.springwatch.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.Bucket;
import com.influxdb.client.domain.BucketRetentionRules;
import com.influxdb.client.domain.Organization;
import com.influxdb.client.domain.Task;
import com.influxdb.client.domain.TaskCreateRequest;
import com.influxdb.client.domain.TaskStatusType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * kxj: InfluxDB bucket 与 downsample 任务初始化
 * P0-3: bucket 必须带 retention,生产 1 个月撑爆磁盘
 * P0-4: downsample 任务把高频原始数据聚合到低频 bucket,控制成本
 */
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

    @Value("${influxdb.self-metrics-bucket:self_metrics}")
    private String selfMetricsBucket;

    @Value("${influxdb.metrics-downsample-bucket:metrics_5m}")
    private String metricsDownsampleBucket;

    @Value("${influxdb.log-downsample-bucket:logs_5m}")
    private String logDownsampleBucket;

    @Value("${influxdb.retention.metrics-seconds:2592000}")
    private int metricsRetentionSeconds;

    @Value("${influxdb.retention.log-seconds:604800}")
    private int logRetentionSeconds;

    @Value("${influxdb.retention.self-metrics-seconds:90000}")
    private int selfMetricsRetentionSeconds;

    @Value("${influxdb.retention.downsample-seconds:31536000}")
    private int downsampleRetentionSeconds;

    @Value("${influxdb.downsample.enabled:true}")
    private boolean downsampleEnabled;

    @Value("${influxdb.downsample.every:5m}")
    private String downsampleEvery;

    @Value("${influxdb.downsample.window:5m}")
    private String downsampleWindow;

    @Value("${influxdb.downsample.task-every:1h}")
    private String downsampleTaskEvery;

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
            ensureBucket(metricsBucket, org, metricsRetentionSeconds);
            ensureBucket(logBucket, org, logRetentionSeconds);
            ensureBucket(selfMetricsBucket, org, selfMetricsRetentionSeconds);
            if (downsampleEnabled) {
                ensureBucket(metricsDownsampleBucket, org, downsampleRetentionSeconds);
                ensureBucket(logDownsampleBucket, org, downsampleRetentionSeconds);
                ensureDownsampleTask("spring-watch-downsample-metrics",
                        metricsBucket, metricsDownsampleBucket,
                        "springboot_metrics", downsampleEvery, downsampleWindow, downsampleTaskEvery, org);
                ensureDownsampleTask("spring-watch-downsample-logs",
                        logBucket, logDownsampleBucket,
                        "app_log", downsampleEvery, downsampleWindow, downsampleTaskEvery, org);
            }
        } catch (Exception e) {
            log.error("[spring-watch: InfluxDB bucket 初始化失败 - error={}]", e.getMessage(), e);
        }
    }

    private void ensureBucket(String bucketName, Organization org, int retentionSeconds) {
        Bucket existing = influxDBClient.getBucketsApi().findBucketByName(bucketName);
        if (existing == null) {
            BucketRetentionRules rules = new BucketRetentionRules()
                    .everySeconds(retentionSeconds);
            Bucket created = influxDBClient.getBucketsApi()
                    .createBucket(bucketName, rules, org);
            log.info("[spring-watch: InfluxDB bucket 创建成功 - name={}, id={}, retentionSeconds={}]",
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
                log.info("[spring-watch: InfluxDB bucket retention 更新 - name={}, retentionSeconds={}->{}]",
                        bucketName, existEvery, retentionSeconds);
            } else {
                log.info("[spring-watch: InfluxDB bucket 已存在 - name={}, retentionSeconds={}]",
                        bucketName, existEvery);
            }
        }
    }

    private void ensureDownsampleTask(String taskName,
                                       String sourceBucket,
                                       String destBucket,
                                       String measurement,
                                       String windowEvery,
                                       String aggWindow,
                                       String taskEvery,
                                       Organization org) {
        List<Task> existing = influxDBClient.getTasksApi().findTasksByOrganization(org);
        boolean alreadyExists = existing.stream()
                .anyMatch(t -> taskName.equals(t.getName()));
        if (alreadyExists) {
            log.info("[spring-watch: InfluxDB downsample task 已存在 - name={}]", taskName);
            return;
        }
        String flux = String.format("""
                option task = {
                  name: "%s",
                  every: %s,
                  offset: 0m
                }

                from(bucket: "%s")
                  |> range(start: -task.every)
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> aggregateWindow(every: %s, fn: mean, createEmpty: false)
                  |> to(bucket: "%s", org: "%s")
                """, taskName, taskEvery, sourceBucket, measurement, aggWindow, destBucket, influxOrg);
        TaskCreateRequest req = new TaskCreateRequest()
                .orgID(org.getId())
                .org(org.getName())
                .status(TaskStatusType.ACTIVE)
                .flux(flux)
                .description("spring-watch auto-managed downsample for " + measurement);
        Task created = influxDBClient.getTasksApi().createTask(req);
        log.info("[spring-watch: InfluxDB downsample task 创建成功 - name={}, id={}, src={}, dest={}, every={}, agg={}]",
                taskName, created.getId(), sourceBucket, destBucket, taskEvery, aggWindow);
    }
}
