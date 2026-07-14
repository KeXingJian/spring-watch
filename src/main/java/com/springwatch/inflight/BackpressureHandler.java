package com.springwatch.inflight;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Setter
@Slf4j
@Component
public class BackpressureHandler {

    public enum Strategy { DROP, RETRY, RETRY_THEN_DROP }

    private Strategy strategy = Strategy.DROP;

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
            return false;
        }
    }
}
