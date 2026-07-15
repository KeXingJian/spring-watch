package com.springwatch.inflight;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "spring-watch.inflight")
public class InflightProperties {

    private boolean enabled = true;

    /**
     * 每 topic 的 partition 数(per-topic 配置,不同 topic 走不同并发度):
     * <ul>
     *   <li>主 topic(高频 / 低频各异):</li>
     *   <ul>
     *     <li>monitor-metrics: 3(高频采集,需并发)</li>
     *     <li>monitor-logs: 3(高频采集,需并发)</li>
     *     <li>monitor-heartbeat: 1(低频心跳,无需并发)</li>
     *   </ul>
     *   <li>DLQ topic(每种事件类型独立 DLQ,各 1 partition):</li>
     *   <ul>
     *     <li>monitor-metrics.dlq: 1</li>
     *     <li>monitor-logs.dlq: 1</li>
     *     <li>monitor-heartbeat.dlq: 1</li>
     *   </ul>
     * </ul>
     */
    private Map<String, Integer> partitions = new HashMap<>(Map.of(
        "monitor-metrics",         3,
        "monitor-logs",            3,
        "monitor-heartbeat",       1,
        "monitor-metrics.dlq",     1,
        "monitor-logs.dlq",        1,
        "monitor-heartbeat.dlq",   1
    ));

    private int bufferCapacity = 50000;

    private Routing routing = new Routing();

    private Consumer consumer = new Consumer();

    @Data
    public static class Routing {
        private Strategy strategy = Strategy.POWER_OF_TWO_CHOICES;

        private Rebalance rebalance = new Rebalance();

        public enum Strategy {
            POWER_OF_TWO_CHOICES,
            WEIGHTED_ROUND_ROBIN,
            ROUND_ROBIN
        }

        @Data
        public static class Rebalance {
            private boolean enabled = true;
            private long intervalSeconds = 60;
            private double pendingThresholdRatio = 1.5;
            private double underloadedThresholdRatio = 0.5;
            private int migrateTopN = 3;
        }
    }

    @Data
    public static class Consumer {
        private int pollMaxBatch = 500;
        private long pollWaitMs = 100;

        private Map<String, Integer> concurrency = new HashMap<>(Map.of(
            "monitor-metrics",         2,
            "monitor-logs",            2,
            "monitor-heartbeat",       1,
            "monitor-metrics.dlq",     1,
            "monitor-logs.dlq",        1,
            "monitor-heartbeat.dlq",   1
        ));
    }
}
