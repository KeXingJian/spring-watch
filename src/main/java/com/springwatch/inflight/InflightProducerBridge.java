package com.springwatch.inflight;

import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.model.event.LogEvent;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

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



    public void sendHeartbeat(HeartbeatEvent event) {
        send(TOPIC_HEARTBEAT, event);
    }

    /**
     * 批量入队 metrics。每批只做一次 P2C 路由,整批落同一 partition。
     * offerBatch 是 all-or-nothing:容量不够整批 reject。
     * 返回成功入队的条数(等于 events.size() 或 0)。
     */
    public int sendMetricBatch(List<MetricEvent> events) {
        return sendBatch(TOPIC_METRICS, events);
    }

    public int sendLogBatch(List<LogEvent> events) {
        return sendBatch(TOPIC_LOGS, events);
    }

    private <T> int sendBatch(String topic, List<T> events) {
        if (events == null || events.isEmpty()) return 0;
        String key = extractKey(events.get(0));
        int partitionId = inflightQueue.route(topic, key);
        Partition p = inflightQueue.getPartition(topic, partitionId);
        int n = events.size();
        try {
            int accepted = p.offerBatch((List<Object>) (List<?>) events);
            if (accepted == n) {
                inflightQueue.metrics().sent(topic, partitionId, accepted);
            } else {
                inflightQueue.metrics().rejected(topic, partitionId, n);
                log.warn("[kxj: 批量入队被拒 - topic={}, partitionId={}, size={}, pending={}/{}]",
                    topic, partitionId, n, p.pending(), p.capacity());
            }
            return accepted;
        } catch (Exception ex) {
            log.error("[kxj: 批量入队异常 - topic={}, partitionId={}, size={}, error={}]",
                topic, partitionId, n, ex.getMessage(), ex);
            inflightQueue.metrics().rejected(topic, partitionId, n);
            return 0;
        }
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
