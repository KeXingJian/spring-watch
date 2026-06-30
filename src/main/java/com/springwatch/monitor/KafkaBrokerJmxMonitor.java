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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 连到 Kafka broker 的 JMX 端口(默认 9999),拉 broker 进程级 + Kafka 服务级 MBean 写 InfluxDB。
 * 适用场景:用户自己起的专有 Kafka,broker 启动时配了
 *   -Dcom.sun.management.jmxremote.port=9999
 *   -Dcom.sun.management.jmxremote.authenticate=false
 *   -Dcom.sun.management.jmxremote.ssl=false
 * 就能直接连;没配的话这个 monitor 会一直报 connect refused,其它逻辑不受影响。
 * 采集的 MBean 大致分四组:
 *   - BrokerTopicMetrics:*PerSec(总 + topic 级):生产/消费消息、字节流量、失败率
 *   - ReplicaManager:URP、OfflinePartitions、PartitionCount(覆盖 URP 的双源)
 *   - ControllerStats:ActiveControllerCount
 *   - JVM(java.lang / OperatingSystem / GC):堆、CPU、GC 次数/耗时
 */
@Slf4j
@Component
public class KafkaBrokerJmxMonitor {

    private final WriteApi writeApi;
    private final MeterRegistry meterRegistry;
    private final WriteParameters writeParameters;

    @Value("${spring-watch.kafka.broker-jmx.url:}")
    private String jmxUrl;

    @Value("${spring-watch.kafka.broker-jmx.username:}")
    private String jmxUsername;

    @Value("${spring-watch.kafka.broker-jmx.password:}")
    private String jmxPassword;

    @Value("${spring-watch.kafka.broker-jmx.poll-interval-sec:30}")
    private long pollIntervalSec;

    @Value("${spring-watch.kafka.broker-jmx.enabled:true}")
    private boolean enabled;

    private ScheduledExecutorService scheduler;
    private JMXConnector connector;
    private final AtomicReference<MBeanServerConnection> connRef = new AtomicReference<>();
    private final AtomicLong lastSuccessEpochMs = new AtomicLong(0L);
    private final AtomicLong lastConnectEpochMs = new AtomicLong(0L);
    @Getter
    private volatile String lastError = "";

    private Counter pollOkCounter;
    private Counter pollFailCounter;
    private Counter connectOkCounter;
    private Counter connectFailCounter;

    public KafkaBrokerJmxMonitor(@Qualifier("infraWriteApi") WriteApi writeApi,
                                 MeterRegistry meterRegistry,
                                 @Value("${influxdb.org}") String org,
                                 @Value("${influxdb.infra-bucket:infra_metrics}") String infraBucket) {
        this.writeApi = writeApi;
        this.meterRegistry = meterRegistry;
        this.writeParameters = new WriteParameters(infraBucket, org, WritePrecision.NS);
    }

    @PostConstruct
    void start() {
        this.pollOkCounter = Counter.builder("spring.watch.kafka.broker_jmx.poll.ok")
                .description("Kafka broker JMX 采集成功次数").register(meterRegistry);
        this.pollFailCounter = Counter.builder("spring.watch.kafka.broker_jmx.poll.fail")
                .description("Kafka broker JMX 采集失败次数").register(meterRegistry);
        this.connectOkCounter = Counter.builder("spring.watch.kafka.broker_jmx.connect.ok")
                .description("Kafka broker JMX 连接成功次数").register(meterRegistry);
        this.connectFailCounter = Counter.builder("spring.watch.kafka.broker_jmx.connect.fail")
                .description("Kafka broker JMX 连接失败次数").register(meterRegistry);

        io.micrometer.core.instrument.Gauge.builder("spring.watch.kafka.broker_jmx.last_success_epoch_ms",
                        lastSuccessEpochMs, AtomicLong::doubleValue)
                .description("最近一次 broker JMX 采集成功 epoch ms")
                .register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("spring.watch.kafka.broker_jmx.last_connect_epoch_ms",
                        lastConnectEpochMs, AtomicLong::doubleValue)
                .description("最近一次 broker JMX 连上 epoch ms(0 表示从未连上)")
                .register(meterRegistry);

        if (!enabled) {
            log.info("[spring-watch: KafkaBrokerJmxMonitor 禁用]");
            return;
        }
        if (jmxUrl == null || jmxUrl.isBlank()) {
            log.info("[spring-watch: KafkaBrokerJmxMonitor 未配置 jmx.url,跳过(设置 spring-watch.kafka.broker-jmx.url 启用)]");
            return;
        }

        ThreadFactory tf = Thread.ofVirtual().name("kafka-broker-jmx-", 0).factory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        // 第一次连接失败也别 panic,让 scheduler 周期重试
        scheduler.scheduleWithFixedDelay(this::pollOnce, 5L, pollIntervalSec, TimeUnit.SECONDS);
        log.info("[spring-watch: KafkaBrokerJmxMonitor 启动 - url={}, interval={}s", jmxUrl, pollIntervalSec);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        closeQuietly();
    }

    private void pollOnce() {
        try {
            ensureConnected();
            MBeanServerConnection conn = connRef.get();
            if (conn == null) return;

            long tsNs = System.currentTimeMillis() * 1_000_000L;
            List<Point> points = new ArrayList<>();

            // 1) Broker topic metrics(总 + 业务 topic 维度)
            collectBrokerTopicMetrics(conn, points, tsNs, null);
            for (String t : List.of("monitor-metrics", "monitor-logs", "monitor-heartbeat")) {
                collectBrokerTopicMetrics(conn, points, tsNs, t);
            }

            // 2) Replica manager
            addAttrIfPresent(conn, points, tsNs, null,
                    "kafka.server:type=ReplicaManager,name=OfflinePartitionsCount",
                    "Value", "broker.offline_partitions");
            // 3) Controller
            addAttrIfPresent(conn, points, tsNs, null,
                    "kafka.controller:type=ControllerStats,name=ActiveControllerCount",
                    "Value", "broker.active_controller");

            // 4) Request queues(看堆积)
            addAttrIfPresent(conn, points, tsNs, "queue=Produce",
                    "kafka.server:type=RequestQueueSize",
                    "Value", "broker.request_queue_produce");
            addAttrIfPresent(conn, points, tsNs, "queue=Fetch",
                    "kafka.server:type=RequestQueueSize",
                    "Value", "broker.request_queue_fetch");
            addAttrIfPresent(conn, points, tsNs, "queue=FetchConsumer",
                    "kafka.server:type=RequestQueueSize",
                    "Value", "broker.request_queue_fetch_consumer");

            // 5) JVM
            addAttrIfPresent(conn, points, tsNs, null,
                    "java.lang:type=Memory",
                    "HeapMemoryUsage.used", "broker.jvm.heap_used");
            addAttrIfPresent(conn, points, tsNs, null,
                    "java.lang:type=Memory",
                    "HeapMemoryUsage.max", "broker.jvm.heap_max");
            addAttrIfPresent(conn, points, tsNs, null,
                    "java.lang:type=Memory",
                    "NonHeapMemoryUsage.used", "broker.jvm.nonheap_used");

            // GC 累加(每个 collector 一条,带 name tag)
            for (ObjectName gc : conn.queryNames(new ObjectName("java.lang:type=GarbageCollector,name=*"), null)) {
                String name = gc.getKeyProperty("name");
                addAttrIfPresent(conn, points, tsNs, "gc=" + name,
                        gc.getCanonicalName(), "CollectionCount", "broker.jvm.gc.count");
                addAttrIfPresent(conn, points, tsNs, "gc=" + name,
                        gc.getCanonicalName(), "CollectionTime", "broker.jvm.gc.time_ms");
            }

            if (!points.isEmpty()) {
                writeApi.writePoints(points, writeParameters);
            }
            lastSuccessEpochMs.set(System.currentTimeMillis());
            lastError = "";
            pollOkCounter.increment();
        } catch (Throwable t) {
            pollFailCounter.increment();
            lastError = t.getClass().getSimpleName() + ":" + t.getMessage();
            // 连接层错误时把连接打掉,下次轮询重连
            if (t instanceof IOException || t.getCause() instanceof IOException) {
                closeQuietly();
            } else {
                log.warn("[spring-watch: Kafka broker JMX 采集失败 - error={}]", t.getMessage());
            }
        }
    }

    /**
     * 拉 BrokerTopicMetrics,既拉总指标(topic 属性缺失),也按指定 topic 拉(给 query 加 topic=xxx filter)。
     * 指标名后缀一般是 PerSec,这里映射成 *_rate 命名;Count/Avg 等保留。
     */
    private void collectBrokerTopicMetrics(MBeanServerConnection conn, List<Point> points, long tsNs, String topic) {
        String filter = topic == null
                ? "kafka.server:type=BrokerTopicMetrics,name=*"
                : "kafka.server:type=BrokerTopicMetrics,name=*,topic=" + topic;
        try {
            Set<ObjectName> names = conn.queryNames(new ObjectName(filter), null);
            for (ObjectName on : names) {
                String kafkaName = on.getKeyProperty("name");
                String mapped = BROKER_TOPIC_METRIC_MAP.get(kafkaName);
                if (mapped == null) continue;
                Map<String, String> extra = new HashMap<>();
                if (topic != null) extra.put("topic", topic);
                addAttrIfPresent(conn, points, tsNs, null, on.getCanonicalName(), "OneMinuteRate", mapped, extra);
            }
        } catch (Exception ignore) {
            // queryNames 在 broker 没起这个 MBean 时会抛 InstanceNotFoundException,正常路径,吞掉
        }
    }

    private static final Map<String, String> BROKER_TOPIC_METRIC_MAP = Map.ofEntries(
            Map.entry("MessagesInPerSec", "broker.messages_in_rate"),
            Map.entry("TotalProduceRequestsPerSec", "broker.produce_requests_rate"),
            Map.entry("BytesInPerSec", "broker.bytes_in_rate"),
            Map.entry("BytesOutPerSec", "broker.bytes_out_rate"),
            Map.entry("FailedProduceRequestsPerSec", "broker.produce_failed_rate"),
            Map.entry("FailedFetchRequestsPerSec", "broker.fetch_failed_rate"),
            Map.entry("InvalidMessageCumulativeCount", "broker.invalid_message_total")
    );

    /**
     * 读一个 MBean 的一个属性,有值就往 points 里塞一条;解析失败静默(可能 MBean 不存在)。
     * extraTagBefore: 在 ObjectName 后追加一段 prop(例如 "queue=Produce"),给同名 MBean 加区分。
     * extraTag: 写到 influx point 上的额外 tag(如 gc=GC1)。
     */
    private void addAttrIfPresent(MBeanServerConnection conn, List<Point> points, long tsNs,
                                  String extraTagBefore, String objectName, String attr,
                                  String metric, Map<String, String> extraTag) {
        try {
            ObjectName on = new ObjectName(objectName + (extraTagBefore == null ? "" : "," + extraTagBefore));
            Object v = conn.getAttribute(on, attr);
            if (v == null) return;
            double d;
            if (v instanceof Number n) d = n.doubleValue();
            else if (v instanceof javax.management.openmbean.CompositeData cd) {
                // 处理 HeapMemoryUsage 这种 CompositeData,key 就是 attr 的 'used' / 'max' 那一截
                String key = attr.contains(".") ? attr.substring(attr.indexOf('.') + 1) : null;
                if (key == null) return;
                Object inner = cd.get(key);
                if (!(inner instanceof Number n)) return;
                d = n.doubleValue();
            } else {
                return;
            }
            Point p = Point.measurement("infra_metrics")
                    .addTag("component", "kafka")
                    .addTag("metric", metric)
                    .addField("value", d)
                    .time(tsNs, WritePrecision.NS);
            if (extraTag != null) {
                for (Map.Entry<String, String> e : extraTag.entrySet()) {
                    p.addTag(e.getKey(), e.getValue());
                }
            }
            points.add(p);
        } catch (Exception ignore) {
            // InstanceNotFoundException / AttributeNotFoundException 都不算错,broker 版本/MBean 注册表可能不一样
        }
    }

    private void addAttrIfPresent(MBeanServerConnection conn, List<Point> points, long tsNs,
                                  String extraTagBefore, String objectName, String attr, String metric) {
        addAttrIfPresent(conn, points, tsNs, extraTagBefore, objectName, attr, metric, null);
    }

    private void ensureConnected() {
        if (connector != null && connRef.get() != null) return;
        try {
            JMXServiceURL url = new JMXServiceURL(jmxUrl);
            Map<String, Object> env = new HashMap<>();
            if (jmxUsername != null && !jmxUsername.isBlank()) {
                env.put(JMXConnector.CREDENTIALS, new String[]{jmxUsername, jmxPassword == null ? "" : jmxPassword});
            }
            JMXConnector c = JMXConnectorFactory.connect(url, env);
            c.connect(env); // JMXConnector 已 connect 后再调一次 connect 是 no-op;但保留以兼容老 driver
            MBeanServerConnection conn = c.getMBeanServerConnection();
            this.connector = c;
            this.connRef.set(conn);
            this.lastConnectEpochMs.set(System.currentTimeMillis());
            connectOkCounter.increment();
            log.info("[spring-watch: Kafka broker JMX 已连接 - url={}", jmxUrl);
        } catch (Throwable t) {
            connectFailCounter.increment();
            lastError = "connect:" + t.getClass().getSimpleName() + ":" + t.getMessage();
            // 不刷 warn,启动期 broker 还没起来会很吵
            log.debug("[spring-watch: Kafka broker JMX 连接失败 - url={}, error={}]", jmxUrl, t.getMessage());
        }
    }

    private void closeQuietly() {
        if (connector != null) {
            try { connector.close(); } catch (Throwable ignore) {}
        }
        connector = null;
        connRef.set(null);
    }
}
