package com.springwatch.test;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.testcontainers.containers.InfluxDBContainer;

/**
 * InfluxDB 容器造数工具 - 测试内直写指标/日志 line protocol。
 * 容器以 v2 setup 模式启动(withBucket/withOrganization/withAdminToken),
 * 这里用 influxdb-client-java v2 API 写入。
 */
public final class InfluxSeeder {

    private InfluxSeeder() {
    }

    /** 写一条 springboot_metrics 指标点 */
    public static void writeMetric(InfluxDBContainer<?> container, long appid,
                                   String metric, double value, long epochSec) {
        Point point = Point.measurement("springboot_metrics")
                .addTag("appid", String.valueOf(appid))
                .addTag("metric", metric)
                .addTag("method", "GET")
                .addField("value", value)
                .time(epochSec, WritePrecision.S);
        write(container, "metrics", point);
    }

    /** 写一条 app_log ERROR 日志点 */
    public static void writeErrorLog(InfluxDBContainer<?> container, long appid,
                                     String message, long epochSec) {
        Point point = Point.measurement("app_log")
                .addTag("appid", String.valueOf(appid))
                .addTag("level", "ERROR")
                .addTag("logger", "OrderService")
                .addTag("threadName", "http-nio")
                .addField("message", message)
                .addField("fingerprint", "fp-" + Math.abs(message.hashCode()))
                .time(epochSec, WritePrecision.S);
        write(container, "logs", point);
    }

    private static void write(InfluxDBContainer<?> container, String bucket, Point point) {
        String org = container.getOrganization();
        String token = container.getAdminToken().orElseThrow(
                () -> new IllegalStateException("InfluxDB 容器未配置 adminToken"));
        try (InfluxDBClient client = InfluxDBClientFactory.create(
                container.getUrl(), token.toCharArray(), org, bucket)) {
            client.getWriteApiBlocking().writePoint(point);
        }
    }
}