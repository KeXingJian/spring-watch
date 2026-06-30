package com.springwatch.monitor;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import com.springwatch.collector.KafkaProducerBridge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.ReplicaInfo;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
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

/**
 * Kafka 集群健康 + spring-watch 生产端统计采集。
 * 与 {@link KafkaLagMonitor} 互补:KafkaLagMonitor 只看消费滞后(差多少),
 * 本类关注 broker 自身健康(URP、offline partition、log dir size)以及 spring-watch
 * 作为 producer 的累计发送/失败计数(供前端算 rate)。
 * 写入 InfluxDB infra_metrics 桶,tag 固定 component=kafka,metric 取以下常量;
 * 这样 InfraPane 自动按 (component, metric) 维度展示,不需要改前端。
 */
@Slf4j
@Component
public class KafkaHealthMonitor {

    private final WriteApi writeApi;
    private final MeterRegistry meterRegistry;
    private final WriteParameters writeParameters;
    private final KafkaProducerBridge producerBridge;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring-watch.kafka.health-monitor.poll-interval-sec:30}")
    private long pollIntervalSec;

    @Value("${spring-watch.kafka.health-monitor.enabled:true}")
    private boolean enabled;

    /** AdminClient 复用 KafkaLagMonitor 的同一组连接属性即可,这里也独立建一份避免耦合。 */
    private ScheduledExecutorService scheduler;
    private AdminClient adminClient;

    private Counter pollOkCounter;
    private Counter pollFailCounter;
    private final AtomicLong lastSuccessEpochMs = new AtomicLong(0L);
    @Getter
    private volatile String lastError = "";

    /** 写入 InfluxDB 时用的 metric 名常量,前端 metricLabels.ts 里要同步翻译。 */
    public static final String M_BROKERS = "kafka.brokers";
    public static final String M_CONTROLLER_ID = "kafka.controller_id";
    public static final String M_TOPICS = "kafka.topics";
    public static final String M_PARTITIONS = "kafka.partitions";
    public static final String M_UNDER_REPLICATED = "kafka.under_replicated_partitions";
    public static final String M_OFFLINE = "kafka.offline_partitions";
    public static final String M_REPLICAS = "kafka.replicas";
    public static final String M_ISR = "kafka.isr";
    public static final String M_LOG_SIZE = "kafka.log_size_bytes";
    public static final String M_PRODUCER_SENT = "kafka.producer.sent";
    public static final String M_PRODUCER_FAILED = "kafka.producer.failed";

    public KafkaHealthMonitor(@Qualifier("infraWriteApi") WriteApi writeApi,
                              MeterRegistry meterRegistry,
                              KafkaProducerBridge producerBridge,
                              @Value("${influxdb.org}") String org,
                              @Value("${influxdb.infra-bucket:infra_metrics}") String infraBucket) {
        this.writeApi = writeApi;
        this.meterRegistry = meterRegistry;
        this.producerBridge = producerBridge;
        this.writeParameters = new WriteParameters(infraBucket, org, WritePrecision.NS);
    }

    @PostConstruct
    void start() {
        this.pollOkCounter = Counter.builder("spring.watch.kafka.health.poll.ok")
                .description("Kafka 健康采集成功次数").register(meterRegistry);
        this.pollFailCounter = Counter.builder("spring.watch.kafka.health.poll.fail")
                .description("Kafka 健康采集失败次数").register(meterRegistry);

        io.micrometer.core.instrument.Gauge.builder("spring.watch.kafka.health.last_success_epoch_ms",
                        lastSuccessEpochMs, AtomicLong::doubleValue)
                .description("最近一次 Kafka 健康采集成功 epoch ms")
                .register(meterRegistry);

        if (!enabled) {
            log.info("[spring-watch: KafkaHealthMonitor 禁用]");
            return;
        }
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "spring-watch-health-monitor");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000);
        this.adminClient = AdminClient.create(props);

        ThreadFactory tf = Thread.ofVirtual().name("kafka-health-monitor-", 0).factory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        scheduler.scheduleWithFixedDelay(this::poll, 5L, pollIntervalSec, TimeUnit.SECONDS);
        log.info("[spring-watch: KafkaHealthMonitor 启动 - interval={}s, bootstrap={}",
                pollIntervalSec, bootstrapServers);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (adminClient != null) adminClient.close();
    }


    private void poll() {
        try {
            // 1) 集群拓扑
            DescribeClusterResult cluster = adminClient.describeCluster();
            Collection<Node> nodes = cluster.nodes().get(8, TimeUnit.SECONDS);
            int brokerCount = nodes.size();
            int controllerId = -1;
            Node controller = cluster.controller().get(8, TimeUnit.SECONDS);
            if (controller != null) controllerId = controller.id();

            // 2) topic 列表(带上内部 topic 排除选项,免得 __consumer_offsets 之类的污染业务视图)
            ListTopicsOptions opts = new ListTopicsOptions().listInternal(false);
            Set<String> topicNames = new HashSet<>(adminClient.listTopics(opts).names().get(8, TimeUnit.SECONDS));
            int topicCount = topicNames.size();

            // 3) 逐个 topic 拉 partition 描述,统计 URP / offline / replicas / isr
            int partitions = 0;
            int underReplicated = 0;
            int offline = 0;
            int replicas = 0;
            int isr = 0;
            if (!topicNames.isEmpty()) {
                Map<String, TopicDescription> descs = adminClient
                        .describeTopics(topicNames)
                        .allTopicNames()
                        .get(8, TimeUnit.SECONDS);
                for (TopicDescription desc : descs.values()) {
                    for (TopicPartitionInfo p : desc.partitions()) {
                        partitions++;
                        int rSize = p.replicas().size();
                        int iSize = p.isr().size();
                        replicas += rSize;
                        isr += iSize;
                        if (p.leader() == null) offline++;
                        if (iSize < rSize) underReplicated++;
                    }
                }
            }

            // 4) log dir size(broker 磁盘占用,所有 topic-partition 在所有 log dir 上的累加)
            long logSizeBytes = 0L;
            if (!nodes.isEmpty()) {
                List<Integer> brokerIds = new ArrayList<>();
                for (Node n : nodes) brokerIds.add(n.id());
                // Kafka 4.x:DescribeLogDirsResult#allDescriptions()
                // 返回 Map<Integer, Map<String, LogDirDescription>>,内层 key 是 log dir 路径(String),
                // 每个 LogDirDescription.replicaInfos() 给出该目录下每个 (topic, partition) 的 ReplicaInfo,后者有 size()。
                Map<Integer, Map<String, LogDirDescription>> logDirs =
                        adminClient.describeLogDirs(brokerIds).allDescriptions().get(8, TimeUnit.SECONDS);
                for (Map<String, LogDirDescription> perBroker : logDirs.values()) {
                    for (LogDirDescription dir : perBroker.values()) {
                        for (ReplicaInfo ri : dir.replicaInfos().values()) {
                            logSizeBytes += ri.size();
                        }
                    }
                }
            }

            // 5) producer 累计计数(从 KafkaProducerBridge 拿,带 topic tag)
            Map<String, Double> sentByTopic = new HashMap<>();
            Map<String, Double> failedByTopic = new HashMap<>();
            for (String topic : topicNames) {
                // 只关心 spring-watch 自己在用的三个 topic
                if (isProducerTopic(topic)) {
                    sentByTopic.put(topic, producerBridge.getSentCount(topic));
                    failedByTopic.put(topic, producerBridge.getFailedCount(topic));
                }
            }

            // 6) 写 InfluxDB
            long tsNs = System.currentTimeMillis() * 1_000_000L;
            List<Point> points = new ArrayList<>();
            addPoint(points, M_BROKERS, brokerCount, tsNs);
            addPoint(points, M_CONTROLLER_ID, controllerId, tsNs);
            addPoint(points, M_TOPICS, topicCount, tsNs);
            addPoint(points, M_PARTITIONS, partitions, tsNs);
            addPoint(points, M_UNDER_REPLICATED, underReplicated, tsNs);
            addPoint(points, M_OFFLINE, offline, tsNs);
            addPoint(points, M_REPLICAS, replicas, tsNs);
            addPoint(points, M_ISR, isr, tsNs);
            addPoint(points, M_LOG_SIZE, logSizeBytes, tsNs);
            for (Map.Entry<String, Double> e : sentByTopic.entrySet()) {
                addPointTagged(points, M_PRODUCER_SENT, e.getKey(), e.getValue(), tsNs);
            }
            for (Map.Entry<String, Double> e : failedByTopic.entrySet()) {
                addPointTagged(points, M_PRODUCER_FAILED, e.getKey(), e.getValue(), tsNs);
            }
            writeApi.writePoints(points, writeParameters);

            lastSuccessEpochMs.set(System.currentTimeMillis());
            lastError = "";
            pollOkCounter.increment();
        } catch (Throwable t) {
            pollFailCounter.increment();
            lastError = t.getClass().getSimpleName() + ":" + t.getMessage();
            log.warn("[spring-watch: Kafka 健康采集失败 - error={}]", t.getMessage());
        }
    }

    private static boolean isProducerTopic(String t) {
        return "monitor-metrics".equals(t) || "monitor-logs".equals(t) || "monitor-heartbeat".equals(t);
    }

    private static void addPoint(List<Point> points, String metric, double value, long tsNs) {
        points.add(Point.measurement("infra_metrics")
                .addTag("component", "kafka")
                .addTag("metric", metric)
                .addField("value", value)
                .time(tsNs, WritePrecision.NS));
    }

    private static void addPointTagged(List<Point> points, String metric, String topic, double value, long tsNs) {
        points.add(Point.measurement("infra_metrics")
                .addTag("component", "kafka")
                .addTag("metric", metric)
                .addTag("topic", topic)
                .addField("value", value)
                .time(tsNs, WritePrecision.NS));
    }
}
