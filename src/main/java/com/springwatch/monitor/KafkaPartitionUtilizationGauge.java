package com.springwatch.monitor;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按 topic × partition 维度采集 Kafka 利用率,供"是否需要调 partition 数"决策。
 * 调研目的(白皮书 0.5):当前 metrics=12 / logs=6 / heartbeat=3 partition 是否冗余?
 * 通过连续观测每 partition 的 produce_rate / consume_rate,1~2 周后看分布决定是否收敛。
 * 数据来源:纯 AdminClient 采样,**不需要 JMX**。3 个核心数据点:
 *   1. end_offset(最新生产位置,AdminClient listOffsets)
 *   2. committed_offset(消费者提交位点,AdminClient listConsumerGroupOffsets)
 *   3. produce_rate / consume_rate = 本轮 end_offset / committed_offset 差值 / 轮询周期
 * 写入 InfluxDB infra_metrics 桶,tag = {component=kafka, metric=kafka.partition.utilization, topic, partition},
 * field = {produced_rate, consumed_rate, lag, end_offset, committed_offset, replicas, isr}。
 * 注意:第一次采集时所有 rate = 0(没有 prev 值),正常。
 */
@Slf4j
@Component
public class KafkaPartitionUtilizationGauge {

    private final WriteApi writeApi;
    private final MeterRegistry meterRegistry;
    private final WriteParameters writeParameters;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring-watch.kafka.partition-utilization.topics:monitor-metrics,monitor-logs,monitor-heartbeat}")
    private String[] topics;

    @Value("${spring-watch.kafka.partition-utilization.group-id:spring-watch}")
    private String groupId;

    @Value("${spring-watch.kafka.partition-utilization.poll-interval-sec:30}")
    private long pollIntervalSec;

    @Value("${spring-watch.kafka.partition-utilization.enabled:true}")
    private boolean enabled;

    /** AdminClient 复用 KafkaLagMonitor 的同一组连接属性即可,这里也独立建一份避免耦合。 */
    private ScheduledExecutorService scheduler;
    private AdminClient adminClient;

    private Counter pollOkCounter;
    private Counter pollFailCounter;
    private final AtomicLong lastSuccessEpochMs = new AtomicLong(0L);
    @Getter
    private volatile String lastError = "";

    /** 上轮 end_offset 快照,key = "topic-partition",用于算 produced_rate。 */
    private final ConcurrentMap<String, Long> prevEndOffsets = new ConcurrentHashMap<>();
    /** 上轮 consumer 提交位点,key 同上,用于算 consumed_rate。 */
    private final ConcurrentMap<String, Long> prevCommittedOffsets = new ConcurrentHashMap<>();

    /** 写入 InfluxDB 时用的 metric 名常量,前端 metricLabels.ts 里要同步翻译。 */
    public static final String M_UTIL = "kafka.partition.utilization";

    public KafkaPartitionUtilizationGauge(@Qualifier("infraWriteApi") WriteApi writeApi,
                                          MeterRegistry meterRegistry,
                                          @Value("${influxdb.org}") String org,
                                          @Value("${influxdb.infra-bucket:infra_metrics}") String infraBucket) {
        this.writeApi = writeApi;
        this.meterRegistry = meterRegistry;
        this.writeParameters = new WriteParameters(infraBucket, org, WritePrecision.NS);
    }

    @PostConstruct
    void start() {
        this.pollOkCounter = Counter.builder("spring.watch.kafka.partition_utilization.poll.ok")
                .description("Kafka partition 利用率采集成功次数").register(meterRegistry);
        this.pollFailCounter = Counter.builder("spring.watch.kafka.partition_utilization.poll.fail")
                .description("Kafka partition 利用率采集失败次数").register(meterRegistry);

        io.micrometer.core.instrument.Gauge.builder("spring.watch.kafka.partition_utilization.last_success_epoch_ms",
                        lastSuccessEpochMs, AtomicLong::doubleValue)
                .description("最近一次 Kafka partition 利用率采集成功 epoch ms")
                .register(meterRegistry);

        if (!enabled) {
            log.info("[kxj: KafkaPartitionUtilizationGauge 禁用]");
            return;
        }
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "spring-watch-partition-utilization");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000);
        this.adminClient = AdminClient.create(props);

        ThreadFactory tf = Thread.ofVirtual().name("kafka-partition-util-", 0).factory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        scheduler.scheduleWithFixedDelay(this::poll, 10L, pollIntervalSec, TimeUnit.SECONDS);
        log.info("[kxj: KafkaPartitionUtilizationGauge 启动 - interval={}s, topics={}, groupId={}",
                pollIntervalSec, String.join(",", topics), groupId);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (adminClient != null) adminClient.close();
    }

    private void poll() {
        try {
            // 1) 拉所有 topic 描述(partition 数 + replicas + isr)
            Map<String, TopicDescription> descs = adminClient.describeTopics(List.of(topics))
                    .allTopicNames().get(8, TimeUnit.SECONDS);

            // 2) 拉 end_offsets(每个 partition 最新生产位置)
            Map<TopicPartition, OffsetSpec> specMap = new HashMap<>();
            for (TopicDescription d : descs.values()) {
                for (TopicPartitionInfo p : d.partitions()) {
                    specMap.put(new TopicPartition(d.name(), p.partition()), OffsetSpec.latest());
                }
            }
            if (specMap.isEmpty()) {
                // topic 还没创建(罕见,但启动时序差),本轮跳过
                return;
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = adminClient
                    .listOffsets(specMap).all().get(8, TimeUnit.SECONDS);

            // 3) 拉 consumer 提交位点(用于 lag + consume_rate)
            Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                    .listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(8, TimeUnit.SECONDS);

            // 4) 算每个 partition 的 produce_rate / consume_rate / lag,写 InfluxDB
            long tsNs = System.currentTimeMillis() * 1_000_000L;
            long intervalSec = pollIntervalSec;
            List<Point> points = new ArrayList<>();
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> e : endOffsets.entrySet()) {
                TopicPartition tp = e.getKey();
                long end = e.getValue().offset();
                long committedOffset = 0L;
                OffsetAndMetadata cm = committed.get(tp);
                if (cm != null) committedOffset = cm.offset();
                long lag = Math.max(0L, end - committedOffset);

                String key = tp.topic() + "-" + tp.partition();
                Long prevEnd = prevEndOffsets.get(key);
                Long prevCommitted = prevCommittedOffsets.get(key);
                double producedRate = 0.0;
                double consumedRate = 0.0;
                if (prevEnd != null && end >= prevEnd) {
                    producedRate = (end - prevEnd) / (double) intervalSec;
                }
                if (prevCommitted != null && committedOffset >= prevCommitted) {
                    consumedRate = (committedOffset - prevCommitted) / (double) intervalSec;
                }
                prevEndOffsets.put(key, end);
                prevCommittedOffsets.put(key, committedOffset);

                // 查 partition 元数据(replicas / isr)
                int replicas = 0, isr = 0;
                TopicDescription d = descs.get(tp.topic());
                if (d != null) {
                    for (TopicPartitionInfo p : d.partitions()) {
                        if (p.partition() == tp.partition()) {
                            replicas = p.replicas().size();
                            isr = p.isr().size();
                            break;
                        }
                    }
                }

                points.add(Point.measurement("infra_metrics")
                        .addTag("component", "kafka")
                        .addTag("metric", M_UTIL)
                        .addTag("topic", tp.topic())
                        .addTag("partition", String.valueOf(tp.partition()))
                        .addField("produced_rate", producedRate)
                        .addField("consumed_rate", consumedRate)
                        .addField("lag", (double) lag)
                        .addField("end_offset", (double) end)
                        .addField("committed_offset", (double) committedOffset)
                        .addField("replicas", (double) replicas)
                        .addField("isr", (double) isr)
                        .time(tsNs, WritePrecision.NS));
            }

            if (!points.isEmpty()) {
                writeApi.writePoints(points, writeParameters);
            }
            lastSuccessEpochMs.set(System.currentTimeMillis());
            lastError = "";
            pollOkCounter.increment();
            log.trace("[kxj: Kafka partition 利用率采集 - partitions={}]", points.size());
        } catch (Throwable t) {
            pollFailCounter.increment();
            lastError = t.getClass().getSimpleName() + ":" + t.getMessage();
            log.warn("[kxj: Kafka partition 利用率采集失败 - error={}]", t.getMessage());
        }
    }
}
