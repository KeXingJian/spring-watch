package com.springwatch.collector;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka 不可用时的内存降级队列。
 * P1-1: 容量由 50000 调到 10000；payload > maxPayloadBytes 截断；新增 Micrometer 计数器。
 */
@Slf4j
@Component
public class KafkaFallbackQueue {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${spring-watch.kafka.fallback-queue.capacity:10000}")
    private int capacity;

    @Value("${spring-watch.kafka.fallback-queue.max-payload-bytes:16384}")
    private int maxPayloadBytes;

    @Value("${spring-watch.kafka.fallback-queue.drain-interval-ms:5000}")
    private long drainIntervalMs;

    @Value("${spring-watch.kafka.fallback-queue.send-timeout-ms:2000}")
    private long sendTimeoutMs;

    @Value("${spring-watch.kafka.fallback-queue.alert-threshold:1000}")
    private int alertThreshold;

    private LinkedBlockingQueue<Record> queue;
    private ScheduledExecutorService drainer;
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalDrained = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong totalTruncated = new AtomicLong(0);
    private volatile boolean running = false;

    private Counter capacityFullCounter;
    private Counter truncatedCounter;
    private Counter staleDroppedCounter;

    public KafkaFallbackQueue(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        this.queue = new LinkedBlockingQueue<>(capacity);
        ThreadFactory tf = Thread.ofVirtual().name("kafka-fallback-drain-", 0).factory();
        this.drainer = Executors.newSingleThreadScheduledExecutor(tf);
        this.drainer.scheduleWithFixedDelay(this::drain, drainIntervalMs, drainIntervalMs, TimeUnit.MILLISECONDS);
        this.running = true;
        this.capacityFullCounter = Counter.builder("spring.watch.kafka.fallback.rejected")
                .description("KafkaFallbackQueue 因容量满丢弃消息次数")
                .register(meterRegistry);
        this.truncatedCounter = Counter.builder("spring.watch.kafka.fallback.truncated")
                .description("KafkaFallbackQueue 因 payload 超限被截断次数")
                .register(meterRegistry);
        this.staleDroppedCounter = Counter.builder("spring.watch.kafka.fallback.stale_dropped")
                .description("KafkaFallbackQueue 因消息停留过久被丢弃次数")
                .register(meterRegistry);
        meterRegistry.gauge("spring.watch.kafka.fallback.queue.size", this, KafkaFallbackQueue::gaugeSize);
        log.info("[spring-watch: KafkaFallbackQueue 启动 - capacity={}, maxPayloadBytes={}, drainInterval={}ms, sendTimeout={}ms, alertThreshold={}]",
                capacity, maxPayloadBytes, drainIntervalMs, sendTimeoutMs, alertThreshold);
    }

    private int gaugeSize() {
        return queue == null ? 0 : queue.size();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (drainer != null) {
            drainer.shutdownNow();
        }
        int pending = queue == null ? 0 : queue.size();
        log.info("[spring-watch: KafkaFallbackQueue 关闭 - pending={}, totalEnqueued={}, totalDrained={}, totalDropped={}, totalTruncated={}]",
                pending, totalEnqueued.get(), totalDrained.get(), totalDropped.get(), totalTruncated.get());
    }

    public void offer(String topic, String key, String payload) {
        if (queue == null) {
            log.warn("[spring-watch: KafkaFallbackQueue 未初始化, 丢弃消息 - topic={}, key={}]", topic, key);
            totalDropped.incrementAndGet();
            capacityFullCounter.increment();
            return;
        }
        if (queue.size() >= capacity) {
            log.error("[spring-watch: KafkaFallbackQueue 已满, 丢弃消息 - topic={}, key={}, size={}]", topic, key, queue.size());
            totalDropped.incrementAndGet();
            capacityFullCounter.increment();
            return;
        }
        String truncated = truncate(payload);
        boolean ok = queue.offer(new Record(topic, key, truncated, Instant.now()));
        if (ok) {
            totalEnqueued.incrementAndGet();
            int size = queue.size();
            if (size >= alertThreshold && size % alertThreshold == 0) {
                log.warn("[spring-watch: KafkaFallbackQueue 堆积告警 - size={}, threshold={}, topic={}]",
                        size, alertThreshold, topic);
            }
        }
    }

    private String truncate(String payload) {
        if (payload == null) {
            return null;
        }
        int byteLen = payload.getBytes(StandardCharsets.UTF_8).length;
        if (byteLen <= maxPayloadBytes) {
            return payload;
        }
        totalTruncated.incrementAndGet();
        truncatedCounter.increment();
        log.warn("[spring-watch: KafkaFallbackQueue payload 截断 - origBytes={}, limit={}", byteLen, maxPayloadBytes);
        int lo = 0, hi = payload.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (payload.substring(0, mid).getBytes(StandardCharsets.UTF_8).length <= maxPayloadBytes) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return payload.substring(0, lo) + "...";
    }

    public int size() {
        return queue == null ? 0 : queue.size();
    }

    private void drain() {
        if (!running || queue == null || queue.isEmpty()) {
            return;
        }
        int sent = 0;
        int failed = 0;
        long startMs = System.currentTimeMillis();
        while (running) {
            Record r = queue.peek();
            if (r == null) break;
            try {
                kafkaTemplate.send(r.topic, r.key, r.payload).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                queue.poll();
                sent++;
            } catch (Exception e) {
                failed++;
                log.warn("[spring-watch: KafkaFallbackQueue 重投失败, 暂停本轮 - topic={}, key={}, error={}]",
                        r.topic, r.key, e.getMessage());
                long ageMs = Duration.between(r.enqueueAt, Instant.now()).toMillis();
                if (ageMs > drainIntervalMs * 10) {
                    log.error("[spring-watch: KafkaFallbackQueue 消息停留过久, 丢头 - topic={}, key={}, ageMs={}",
                            r.topic, r.key, ageMs);
                    queue.poll();
                    totalDropped.incrementAndGet();
                    staleDroppedCounter.increment();
                }
                break;
            }
        }
        if (sent > 0 || failed > 0) {
            totalDrained.addAndGet(sent);
            log.info("[spring-watch: KafkaFallbackQueue drain - sent={}, failed={}, pending={}, costMs={}]",
                    sent, failed, queue.size(), System.currentTimeMillis() - startMs);
        }
    }

    private record Record(String topic, String key, String payload, Instant enqueueAt) {}
}
