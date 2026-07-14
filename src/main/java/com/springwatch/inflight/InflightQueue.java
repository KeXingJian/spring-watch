package com.springwatch.inflight;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@ConditionalOnProperty(name = "spring-watch.inflight.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(InflightProperties.class)
@RequiredArgsConstructor
public class InflightQueue {

    public static final String TOPIC_METRICS = "monitor-metrics";
    public static final String TOPIC_LOGS = "monitor-logs";
    public static final String TOPIC_HEARTBEAT = "monitor-heartbeat";
    public static final String TOPIC_DLQ_METRICS = "monitor-metrics.dlq";
    public static final String TOPIC_DLQ_LOGS = "monitor-logs.dlq";
    public static final String TOPIC_DLQ_HEARTBEAT = "monitor-heartbeat.dlq";

    private final InflightProperties props;
    private final InflightMetrics metrics;

    /** topic → 该 topic 的 partition 数组(各 topic partition 数独立) */
    private final ConcurrentMap<String, Partition[]> topicPartitions = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        Map<String, Integer> topicPartitionCfg = props.getPartitions();

        for (Map.Entry<String, Integer> e : topicPartitionCfg.entrySet()) {
            String topic = e.getKey();
            int K = e.getValue();
            if (K < 1) K = 1;
            Partition[] parts = new Partition[K];
            for (int i = 0; i < K; i++) {
                metrics.registerPartition(topic, i, props.getBufferCapacity());
                InflightBuffer buf = new InflightBuffer(topic, i, props.getBufferCapacity(), metrics);
                Partition p = new Partition(topic, i, buf);
                parts[i] = p;
            }
            topicPartitions.put(topic, parts);
        }

        log.info("[kxj: InflightQueue 启动 - topics={}, bufferCapacity={}]",
            topicPartitions.keySet(), props.getBufferCapacity());
    }

    @PreDestroy
    void shutdown() {
        log.info("[kxj: InflightQueue 关闭 - sent={}, rejected={}]",
            metrics.totalSent(), metrics.totalRejected());
    }

    public Partition[] partitionsOf(String topic) {
        return topicPartitions.get(topic);
    }

    public Partition getPartition(String topic, int partitionId) {
        Partition[] arr = topicPartitions.get(topic);
        if (arr == null || partitionId < 0 || partitionId >= arr.length) {
            throw new IllegalArgumentException("unknown topic/partition: " + topic + "/" + partitionId);
        }
        return arr[partitionId];
    }

    public int partitionCount(String topic) {
        Partition[] arr = topicPartitions.get(topic);
        return arr == null ? 0 : arr.length;
    }

    public List<String> activeTopics() {
        return new ArrayList<>(topicPartitions.keySet());
    }

    /**
     * 简单的两选一路由(基础版,PartitionRouter 在 M1.5 引入)。
     */
    public int route(String topic, String key) {
        Partition[] arr = topicPartitions.get(topic);
        if (arr == null) {
            throw new IllegalArgumentException("unknown topic: " + topic);
        }
        int K = arr.length;
        if (K == 1) return 0;
        if (key == null || key.isEmpty()) {
            return ThreadLocalRandom.current().nextInt(K);
        }
        int i = ThreadLocalRandom.current().nextInt(K);
        int j = (i + 1 + ThreadLocalRandom.current().nextInt(K - 1)) % K;
        long pi = arr[i].pending();
        long pj = arr[j].pending();
        return pi <= pj ? i : j;
    }

    public InflightMetrics metrics() { return metrics; }
}
