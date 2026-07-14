package com.springwatch.inflight;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Setter
@Slf4j
@Component
public class BackpressureHandler {

    public enum Strategy { DROP, RETRY, RETRY_THEN_DROP }

    private Strategy strategy = Strategy.DROP;

    private final MeterRegistry meterRegistry;

    private Counter dlqPersistedCounter;
    private Counter dlqPersistFailCounter;

    public BackpressureHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        this.dlqPersistedCounter = Counter.builder("spring.watch.consumer.dlq.persisted")
                .description("v2.0 语义:InflightQueue 背压后被 DROP 的条数(旧版写 DLQ topic 持久化,新架构为内存丢弃)")
                .register(meterRegistry);
        this.dlqPersistFailCounter = Counter.builder("spring.watch.consumer.dlq.persist_fail")
                .description("v2.0 语义:InflightQueue 背压处理失败次数")
                .register(meterRegistry);
    }

    public void handle(String topic, int partitionId, Object payload, BackpressureException ex) {
        switch (strategy) {
            case DROP -> drop(topic, partitionId, ex);
            case RETRY -> retry(topic, partitionId, payload, ex, 1);
            case RETRY_THEN_DROP -> {
                if (!retry(topic, partitionId, payload, ex, 1)) {
                    drop(topic, partitionId, ex);
                }
            }
        }
    }

    private void drop(String topic, int partitionId, BackpressureException ex) {
        dlqPersistedCounter.increment();
        log.warn("[kxj: 背压丢弃 - topic={}, partitionId={}, reason={}]",
            topic, partitionId, ex.getReason());
    }

    private boolean retry(String topic, int partitionId, Object payload,
                          BackpressureException ex, int attempts) {
        try {
            Thread.sleep(10L);
            log.info("[kxj: 背压重试 - topic={}, partitionId={}, attempts={}]", topic, partitionId, attempts);
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            dlqPersistFailCounter.increment();
            return false;
        }
    }
}
