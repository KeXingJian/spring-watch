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
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;

/**
 * 把 spring-watch 自己这个 KafkaProducer 客户端内部的 JMX 指标(由 Kafka client 自动注册的
 * producer-metrics / producer-topic-metrics MBean)按白名单抽取,写进 InfluxDB,前端在 InfraPane
 * 上能看到 record-send-rate / batch-size-avg / compression-rate / buffer-available-bytes 这类
 * 内部状态,跟外部 broker 视角互补。
 * 为什么需要自己抓:Spring Kafka 的 KafkaTemplate 不会把 Kafka client 内部 Metric 对象挂到
 * Micrometer 上,默认只能看到我们自己埋的 Counter;Kafka 内部那 100+ 指标(stream-thread /
 * request-latency / io-wait-time 等)只通过 Producer.metrics() 这个 Map 暴露,必须主动拉。
 */
@Slf4j
@Component
public class KafkaProducerJmxCollector {

    private final WriteApi writeApi;
    private final MeterRegistry meterRegistry;
    private final WriteParameters writeParameters;
    private final ProducerFactory<String, String> producerFactory;

    @Value("${spring-watch.kafka.producer-jmx.enabled:true}")
    private boolean enabled;

    @Value("${spring-watch.kafka.producer-jmx.poll-interval-sec:30}")
    private long pollIntervalSec;

    private ScheduledExecutorService scheduler;
    private Producer<String, String> metricsProducer;

    private Counter pollOkCounter;
    private Counter pollFailCounter;
    private final AtomicLong lastSuccessEpochMs = new AtomicLong(0L);


    public KafkaProducerJmxCollector(@Qualifier("infraWriteApi") WriteApi writeApi,
                                     MeterRegistry meterRegistry,
                                     ProducerFactory<String, String> producerFactory,
                                     @Value("${influxdb.org}") String org,
                                     @Value("${influxdb.infra-bucket:infra_metrics}") String infraBucket) {
        this.writeApi = writeApi;
        this.meterRegistry = meterRegistry;
        this.producerFactory = producerFactory;
        this.writeParameters = new WriteParameters(infraBucket, org, WritePrecision.NS);
    }

    @PostConstruct
    void start() {
        this.pollOkCounter = Counter.builder("spring.watch.kafka.producer_jmx.poll.ok")
                .description("Kafka producer 内部 JMX 指标采集成功次数").register(meterRegistry);
        this.pollFailCounter = Counter.builder("spring.watch.kafka.producer_jmx.poll.fail")
                .description("Kafka producer 内部 JMX 指标采集失败次数").register(meterRegistry);

        io.micrometer.core.instrument.Gauge.builder("spring.watch.kafka.producer_jmx.last_success_epoch_ms",
                        lastSuccessEpochMs, AtomicLong::doubleValue)
                .description("最近一次 Kafka producer JMX 采集成功 epoch ms")
                .register(meterRegistry);

        if (!enabled) {
            log.info("[spring-watch: KafkaProducerJmxCollector 禁用]");
            return;
        }
        try {
            // createProducer() 会从 factory 缓存里取,没缓存则新建。spring.kafka.producer.client-id
            // 没设的话每次都新建;为了避免反复创建,这里只调一次,长期持有引用专供 metrics()。
            this.metricsProducer = producerFactory.createProducer();
        } catch (Throwable t) {
            log.warn("[spring-watch: KafkaProducerJmxCollector 启动失败,producer 不可用 - error={}]", t.getMessage());
            return;
        }

        ThreadFactory tf = Thread.ofVirtual().name("kafka-producer-jmx-", 0).factory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        scheduler.scheduleWithFixedDelay(this::poll, 5L, pollIntervalSec, TimeUnit.SECONDS);
        log.info("[spring-watch: KafkaProducerJmxCollector 启动 - interval={}s", pollIntervalSec);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (metricsProducer != null) {
            // Kafka 4.x:Producer 只有 close() 和 close(Duration),没有 close(long, TimeUnit)
            try { metricsProducer.close(Duration.ofSeconds(2)); } catch (Throwable ignore) {}
        }
    }

    /**
     * Kafka producer metrics() 输出的指标名都是 `xxx-rate` / `xxx-avg` / `xxx-total` 这种风格。
     * 这里给一份白名单 + 归一化映射,把 Kafka 内部名换成前端 INFRA_LABELS 已经认识的中文 key。
     * 没在白名单的指标(比如 io-wait-ratio-ns 之类不常用的)忽略,避免把 InfluxDB 撑爆。
     */
    private static final Map<String, String> METRIC_MAP = Map.ofEntries(
            // 网络 / 吞吐
            Map.entry("record-send-rate", "producer.record_send_rate"),
            Map.entry("record-error-rate", "producer.record_error_rate"),
            Map.entry("record-retry-rate", "producer.record_retry_rate"),
            Map.entry("byte-rate", "producer.byte_rate"),
            Map.entry("outgoing-byte-rate", "producer.outgoing_byte_rate"),
            // 批
            Map.entry("batch-size-avg", "producer.batch_size_avg"),
            Map.entry("batch-size-max", "producer.batch_size_max"),
            Map.entry("records-per-request-avg", "producer.records_per_request_avg"),
            // 压缩
            Map.entry("compression-rate", "producer.compression_rate"),
            // 缓冲
            Map.entry("buffer-available-bytes", "producer.buffer_available_bytes"),
            Map.entry("buffer-total-bytes", "producer.buffer_total_bytes"),
            Map.entry("buffer-exhausted-rate", "producer.buffer_exhausted_rate"),
            // 请求 / 延迟
            Map.entry("request-rate", "producer.request_rate"),
            Map.entry("request-latency-avg", "producer.request_latency_avg"),
            Map.entry("request-latency-max", "producer.request_latency_max"),
            // 等待线程 / I/O wait
            Map.entry("waiting-threads", "producer.waiting_threads"),
            Map.entry("io-wait-time-ns-avg", "producer.io_wait_time_avg"),
            // 阻塞 / 元数据
            Map.entry("metadata-age", "producer.metadata_age")
    );

    /**
     * 既匹配"全量"(没带 topic tag)又匹配"按 topic"(带 topic tag)。
     * Kafka 客户端对 record-send-rate / record-error-rate / byte-rate 这几个指标会同时输出
     * 总量版和 topic 版(都叫同一个 metric name,只在 tag 上区分),所以两个都要写。
     */
    private static final Set<String> TOPIC_SCOPED = Set.of(
            "record-send-rate",
            "record-error-rate",
            "byte-rate"
    );

    private void poll() {
        try {
            Map<MetricName, ? extends Metric> metrics = metricsProducer.metrics();
            long tsNs = System.currentTimeMillis() * 1_000_000L;
            List<Point> points = new ArrayList<>();
            int matched = 0;
            for (Map.Entry<MetricName, ? extends Metric> e : metrics.entrySet()) {
                MetricName mn = e.getKey();
                String kafkaName = mn.name();
                String mapped = METRIC_MAP.get(kafkaName);
                if (mapped == null) continue;
                Object v = e.getValue().metricValue();
                if (!(v instanceof Number n)) continue;
                matched++;
                String topicTag = TOPIC_SCOPED.contains(kafkaName) ? mn.tags().get("topic") : null;
                if (topicTag != null && !topicTag.isEmpty()) {
                    addTagged(points, mapped, topicTag, n.doubleValue(), tsNs);
                } else {
                    addPoint(points, mapped, n.doubleValue(), tsNs);
                }
            }
            if (!points.isEmpty()) {
                writeApi.writePoints(points, writeParameters);
            }
            lastSuccessEpochMs.set(System.currentTimeMillis());
            pollOkCounter.increment();
            log.trace("[spring-watch: Kafka producer JMX 采集 - total={}, matched={}]", metrics.size(), matched);
        } catch (Throwable t) {
            pollFailCounter.increment();
            log.warn("[spring-watch: Kafka producer JMX 采集失败 - error={}]", t.getMessage());
        }
    }

    private static void addPoint(List<Point> points, String metric, double value, long tsNs) {
        points.add(Point.measurement("infra_metrics")
                .addTag("component", "kafka")
                .addTag("metric", metric)
                .addField("value", value)
                .time(tsNs, WritePrecision.NS));
    }

    private static void addTagged(List<Point> points, String metric, String topic, double value, long tsNs) {
        points.add(Point.measurement("infra_metrics")
                .addTag("component", "kafka")
                .addTag("metric", metric)
                .addTag("topic", topic)
                .addField("value", value)
                .time(tsNs, WritePrecision.NS));
    }
}
