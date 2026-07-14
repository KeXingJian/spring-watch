package com.springwatch.inflight;

import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.model.event.LogEvent;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "spring-watch.inflight.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class InflightProducerBridge {

    public static final String TOPIC_METRICS = "monitor-metrics";
    public static final String TOPIC_LOGS = "monitor-logs";
    public static final String TOPIC_HEARTBEAT = "monitor-heartbeat";

    private final InflightQueue inflightQueue;
    private final BackpressureHandler backpressureHandler;

    public void sendMetric(MetricEvent event) {
        send(TOPIC_METRICS, event);
    }

    public void sendLog(LogEvent event) {
        send(TOPIC_LOGS, event);
    }

    public void sendHeartbeat(HeartbeatEvent event) {
        send(TOPIC_HEARTBEAT, event);
    }

    private void send(String topic, Object event) {
        if (event == null) return;
        String key = extractKey(event);

        int partitionId = inflightQueue.route(topic, key);
        Partition p = inflightQueue.getPartition(topic, partitionId);
        try {
            boolean ok = p.offer(event);
            if (ok) {
                inflightQueue.metrics().sent(topic, partitionId);
            } else {
                BackpressureException ex = new BackpressureException(topic, partitionId,
                    BackpressureException.Reason.IN_FLIGHT_FULL,
                    "in-flight buffer full: " + topic + " p" + partitionId);
                backpressureHandler.handle(topic, partitionId, event, ex);
                inflightQueue.metrics().rejected(topic, partitionId);
            }
        } catch (Exception ex) {
            log.error("[kxj: InflightProducerBridge send 异常 - topic={}, key={}, error={}]",
                topic, key, ex.getMessage(), ex);
            inflightQueue.metrics().rejected(topic, partitionId);
        }
    }

    private static String extractKey(Object event) {
        if (event instanceof MetricEvent m && m.getAppid() != null) {
            return String.valueOf(m.getAppid());
        }
        if (event instanceof LogEvent l && l.getAppid() != null) {
            return String.valueOf(l.getAppid());
        }
        if (event instanceof HeartbeatEvent h && h.getAppid() != null) {
            return String.valueOf(h.getAppid());
        }
        return null;
    }
}
