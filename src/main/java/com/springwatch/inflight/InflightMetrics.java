package com.springwatch.inflight;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@Component
public class InflightMetrics {

    private static final String PREFIX = "spring.watch.inflight.";

    private final MeterRegistry registry;

    private final ConcurrentMap<String, Counter> sentCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> rejectedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> drainedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributionSummary> batchSizeSummaries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LongAdder> pendingGauges = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> capacityCache = new ConcurrentHashMap<>();

    public InflightMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void registerPartition(String topic, int partitionId, int capacity) {
        String key = keyOf(topic, partitionId);
        capacityCache.put(key, capacity);

        if (!sentCounters.containsKey(key)) {
            sentCounters.put(key, Counter.builder(PREFIX + "producer.sent")
                .tag("topic", topic).tag("partition", String.valueOf(partitionId))
                .description("累计入队条数")
                .register(registry));
        }
        if (!rejectedCounters.containsKey(key)) {
            rejectedCounters.put(key, Counter.builder(PREFIX + "producer.rejected")
                .tag("topic", topic).tag("partition", String.valueOf(partitionId))
                .description("L1 拒绝条数(背压)")
                .register(registry));
        }
        if (!drainedCounters.containsKey(key)) {
            drainedCounters.put(key, Counter.builder(PREFIX + "producer.drained")
                .tag("topic", topic).tag("partition", String.valueOf(partitionId))
                .description("累计消费条数")
                .register(registry));
        }
        if (!batchSizeSummaries.containsKey(key)) {
            batchSizeSummaries.put(key, DistributionSummary.builder(PREFIX + "consumer.batch.size")
                .tag("topic", topic).tag("partition", String.valueOf(partitionId))
                .description("每批消费条数")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
        }
        if (!pendingGauges.containsKey(key)) {
            LongAdder adder = new LongAdder();
            pendingGauges.put(key, adder);
            Gauge.builder(PREFIX + "queue.pending", adder, LongAdder::sum)
                .tag("topic", topic).tag("partition", String.valueOf(partitionId))
                .description("当前堆积")
                .register(registry);
            Gauge.builder(PREFIX + "queue.capacity", adder, a -> capacityCache.getOrDefault(key, 0))
                .tag("topic", topic).tag("partition", String.valueOf(partitionId))
                .description("容量上限")
                .register(registry);
        }
    }

    public void sent(String topic, int partitionId) {
        Counter c = sentCounters.get(keyOf(topic, partitionId));
        if (c != null) c.increment();
    }

    public void rejected(String topic, int partitionId) {
        Counter c = rejectedCounters.get(keyOf(topic, partitionId));
        if (c != null) c.increment();
    }

    public void drained(String topic, int partitionId, int n) {
        Counter c = drainedCounters.get(keyOf(topic, partitionId));
        if (c != null) c.increment(n);
    }

    public void recordBatchSize(String topic, int partitionId, int n) {
        DistributionSummary s = batchSizeSummaries.get(keyOf(topic, partitionId));
        if (s != null) s.record(n);
    }

    public void updatePending(String topic, int partitionId, int size) {
        LongAdder a = pendingGauges.get(keyOf(topic, partitionId));
        if (a != null) {
            a.reset();
            a.add(size);
        }
    }

    public long totalSent() {
        return Math.round(sentCounters.values().stream().mapToDouble(Counter::count).sum());
    }

    public long totalRejected() {
        return Math.round(rejectedCounters.values().stream().mapToDouble(Counter::count).sum());
    }

    private static String keyOf(String topic, int partitionId) {
        return topic + ":" + partitionId;
    }
}
