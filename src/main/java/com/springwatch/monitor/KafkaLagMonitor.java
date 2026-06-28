package com.springwatch.monitor;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class KafkaLagMonitor {

    private final WriteApi writeApi;
    private final MeterRegistry meterRegistry;
    private final WriteParameters writeParameters;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring-watch.kafka.lag-monitor.group-id:spring-watch}")
    private String groupId;

    @Value("${spring-watch.kafka.lag-monitor.topics:monitor-metrics,monitor-logs,monitor-heartbeat}")
    private String[] topics;

    @Value("${spring-watch.kafka.lag-monitor.poll-interval-sec:30}")
    private long pollIntervalSec;

    @Value("${spring-watch.kafka.lag-monitor.enabled:true}")
    private boolean enabled;

    private ScheduledExecutorService scheduler;
    private AdminClient adminClient;
    private Counter pollOkCounter;
    private Counter pollFailCounter;
    private final AtomicLong lastSuccessEpochMs = new AtomicLong(0L);
    private volatile String lastError = "";

    public KafkaLagMonitor(WriteApi writeApi,
                           MeterRegistry meterRegistry,
                           @Value("${influxdb.org}") String org,
                           @Value("${influxdb.infra-bucket:infra_metrics}") String infraBucket) {
        this.writeApi = writeApi;
        this.meterRegistry = meterRegistry;

        this.writeParameters = new WriteParameters(infraBucket, org, WritePrecision.NS);
    }

    @PostConstruct
    void start() {
        this.pollOkCounter = Counter.builder("spring.watch.kafka.lag.poll.ok")
                .description("Kafka lag 采集成功次数").register(meterRegistry);
        this.pollFailCounter = Counter.builder("spring.watch.kafka.lag.poll.fail")
                .description("Kafka lag 采集失败次数").register(meterRegistry);

        io.micrometer.core.instrument.Gauge.builder("spring.watch.kafka.lag.last_success_epoch_ms",
                        lastSuccessEpochMs, AtomicLong::doubleValue)
                .description("最近一次 Kafka lag 采集成功 epoch ms")
                .register(meterRegistry);

        if (!enabled) {
            log.info("[spring-watch: KafkaLagMonitor 禁用]");
            return;
        }
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "spring-watch-lag-monitor");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000);
        this.adminClient = AdminClient.create(props);

        ThreadFactory tf = Thread.ofVirtual().name("kafka-lag-monitor-", 0).factory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        scheduler.scheduleWithFixedDelay(this::poll, 10L, pollIntervalSec, TimeUnit.SECONDS);
        log.info("[spring-watch: KafkaLagMonitor 启动 - interval={}s, topics={}, groupId={}",
                pollIntervalSec, String.join(",", topics), groupId);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (adminClient != null) adminClient.close();
    }

    public long getLastSuccessEpochMs() {
        return lastSuccessEpochMs.get();
    }

    public String getLastError() {
        return lastError;
    }

    private void poll() {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                    .listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(8, TimeUnit.SECONDS);

            Set<TopicPartition> allPartitions = new HashSet<>();
            for (String topic : topics) {
                ListConsumerGroupOffsetsResult dummy = null;
                int partitionCount = guessPartitionCount(topic);
                for (int i = 0; i < partitionCount; i++) {
                    allPartitions.add(new TopicPartition(topic, i));
                }
            }

            Map<TopicPartition, OffsetSpec> specMap = new HashMap<>();
            for (TopicPartition tp : allPartitions) {
                specMap.put(tp, OffsetSpec.latest());
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = adminClient
                    .listOffsets(specMap)
                    .all()
                    .get(8, TimeUnit.SECONDS);

            long tsNs = System.currentTimeMillis() * 1_000_000L;
            List<Point> points = new ArrayList<>();
            long totalLag = 0L;
            int totalPartitions = 0;
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> e : endOffsets.entrySet()) {
                TopicPartition tp = e.getKey();
                long end = e.getValue().offset();
                long committedOffset = 0L;
                OffsetAndMetadata cm = committed.get(tp);
                if (cm != null) committedOffset = cm.offset();
                long lag = Math.max(0L, end - committedOffset);
                totalLag += lag;
                totalPartitions++;

                Point p = Point.measurement("infra_metrics")
                        .addTag("component", "kafka")
                        .addTag("metric", "consumer.lag")
                        .addTag("topic", tp.topic())
                        .addTag("partition", String.valueOf(tp.partition()))
                        .addTag("group", groupId)
                        .addField("value", (double) lag)
                        .time(tsNs, WritePrecision.NS);
                points.add(p);
            }
            if (!points.isEmpty()) {
                Point sum = Point.measurement("infra_metrics")
                        .addTag("component", "kafka")
                        .addTag("metric", "consumer.lag.total")
                        .addTag("group", groupId)
                        .addField("value", (double) totalLag)
                        .time(tsNs, WritePrecision.NS);
                points.add(sum);

                Point parts = Point.measurement("infra_metrics")
                        .addTag("component", "kafka")
                        .addTag("metric", "consumer.partitions")
                        .addTag("group", groupId)
                        .addField("value", (double) totalPartitions)
                        .time(tsNs, WritePrecision.NS);
                points.add(parts);

                writeApi.writePoints(points, writeParameters);
            }
            lastSuccessEpochMs.set(System.currentTimeMillis());
            lastError = "";
            pollOkCounter.increment();
        } catch (Throwable t) {
            pollFailCounter.increment();
            lastError = t.getClass().getSimpleName() + ":" + t.getMessage();
            log.warn("[spring-watch: Kafka lag 采集失败 - error={}]", t.getMessage());
        }
    }

    private int guessPartitionCount(String topic) {
        try {
            var descs = adminClient.describeTopics(List.of(topic))
                    .allTopicNames()
                    .get(5, TimeUnit.SECONDS);
            if (descs.containsKey(topic)) {
                return descs.get(topic).partitions().size();
            }
        } catch (Throwable ignore) {
        }
        return 1;
    }
}
