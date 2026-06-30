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
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final LagMonitorProperties props;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private ScheduledExecutorService scheduler;
    private AdminClient adminClient;
    private Counter pollOkCounter;
    private Counter pollFailCounter;
    private final AtomicLong lastSuccessEpochMs = new AtomicLong(0L);
    private volatile String lastError = "";

    public KafkaLagMonitor(@Qualifier("infraWriteApi") WriteApi writeApi,
                           MeterRegistry meterRegistry,
                           LagMonitorProperties props,
                           @Value("${influxdb.org}") String org,
                           @Value("${influxdb.infra-bucket:infra_metrics}") String infraBucket) {
        this.writeApi = writeApi;
        this.meterRegistry = meterRegistry;
        this.props = props;
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

        // 配置体检:每个 topic 必须显式指定 group(否则 lag 会显示"累计消息数"而不是真实堆积)
        Map<String, String> groups = props.getGroups() != null ? props.getGroups() : Map.of();
        for (String topic : props.getTopics()) {
            if (!groups.containsKey(topic)) {
                log.warn("[spring-watch: KafkaLagMonitor] topic '{}' 没在 lag-monitor.groups 里指定 consumer group,"
                                + " 默认会用 '{}' 查 committed offset。"
                                + " 如果该 topic 的实际消费者不在这个 group,lag 数字会等于 log end offset(累计消息数),不是真实堆积",
                        topic, props.getGroupId());
            }
        }

        if (!props.isEnabled()) {
            log.info("[spring-watch: KafkaLagMonitor 禁用]");
            return;
        }
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        adminProps.put(AdminClientConfig.CLIENT_ID_CONFIG, "spring-watch-lag-monitor");
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000);
        this.adminClient = AdminClient.create(adminProps);

        ThreadFactory tf = Thread.ofVirtual().name("kafka-lag-monitor-", 0).factory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        scheduler.scheduleWithFixedDelay(this::poll, 10L, props.getPollIntervalSec(), TimeUnit.SECONDS);
        log.info("[spring-watch: KafkaLagMonitor 启动 - interval={}s, topics={}, groupId={}, topicGroups={}",
                props.getPollIntervalSec(), String.join(",", props.getTopics()),
                props.getGroupId(), props.getGroups());
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
            String groupId = props.getGroupId();
            String[] topics = props.getTopics();
            Map<String, String> topicGroups = props.getGroups() != null ? props.getGroups() : Map.of();

            Map<TopicPartition, String> tpToGroup = new HashMap<>();
            Set<TopicPartition> allPartitions = new HashSet<>();
            for (String topic : topics) {
                int partitionCount = guessPartitionCount(topic);
                for (int i = 0; i < partitionCount; i++) {
                    TopicPartition tp = new TopicPartition(topic, i);
                    allPartitions.add(tp);
                    String g = topicGroups.getOrDefault(topic, groupId);
                    tpToGroup.put(tp, g);
                }
            }

            Map<String, Map<TopicPartition, OffsetAndMetadata>> committedByGroup = new HashMap<>();
            Set<String> distinctGroups = new HashSet<>(tpToGroup.values());
            for (String g : distinctGroups) {
                try {
                    committedByGroup.put(g, adminClient
                            .listConsumerGroupOffsets(g)
                            .partitionsToOffsetAndMetadata()
                            .get(8, TimeUnit.SECONDS));
                } catch (Throwable ignore) {
                    committedByGroup.put(g, new HashMap<>());
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
            Map<String, Long> totalLagByGroup = new HashMap<>();
            Map<String, Map<String, Long>> lagByTopicAndGroup = new HashMap<>();
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> e : endOffsets.entrySet()) {
                TopicPartition tp = e.getKey();
                String g = tpToGroup.getOrDefault(tp, groupId);
                Map<TopicPartition, OffsetAndMetadata> committed = committedByGroup.getOrDefault(g, Map.of());
                long end = e.getValue().offset();
                long committedOffset = 0L;
                OffsetAndMetadata cm = committed.get(tp);
                if (cm != null) committedOffset = cm.offset();
                long lag = Math.max(0L, end - committedOffset);
                totalLagByGroup.merge(g, lag, Long::sum);
                lagByTopicAndGroup
                        .computeIfAbsent(tp.topic(), k -> new HashMap<>())
                        .merge(g, lag, Long::sum);

                Point p = Point.measurement("infra_metrics")
                        .addTag("component", "kafka")
                        .addTag("metric", "consumer.lag")
                        .addTag("topic", tp.topic())
                        .addTag("partition", String.valueOf(tp.partition()))
                        .addTag("group", g)
                        .addField("value", (double) lag)
                        .time(tsNs, WritePrecision.NS);
                points.add(p);
            }
            if (!points.isEmpty()) {
                for (Map.Entry<String, Long> e : totalLagByGroup.entrySet()) {
                    Point sum = Point.measurement("infra_metrics")
                            .addTag("component", "kafka")
                            .addTag("metric", "consumer.lag.total")
                            .addTag("group", e.getKey())
                            .addField("value", (double) e.getValue())
                            .time(tsNs, WritePrecision.NS);
                    points.add(sum);
                }

                for (Map.Entry<String, Map<String, Long>> topicEntry : lagByTopicAndGroup.entrySet()) {
                    String topic = topicEntry.getKey();
                    for (Map.Entry<String, Long> groupEntry : topicEntry.getValue().entrySet()) {
                        Point tp = Point.measurement("infra_metrics")
                                .addTag("component", "kafka")
                                .addTag("metric", "consumer.lag.topic")
                                .addTag("topic", topic)
                                .addTag("group", groupEntry.getKey())
                                .addField("value", (double) groupEntry.getValue())
                                .time(tsNs, WritePrecision.NS);
                        points.add(tp);
                    }
                }

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
