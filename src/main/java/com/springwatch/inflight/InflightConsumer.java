package com.springwatch.inflight;

import com.springwatch.alerter.AsyncAlertExecutor;
import com.springwatch.consumer.BatchAlertConsumer;
import com.springwatch.model.event.LogEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "spring-watch.inflight.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class InflightConsumer {

    private final InflightQueue inflightQueue;
    private final InflightProperties props;
    private final MetricEventWriter metricWriter;
    private final LogEventWriter logWriter;
    private final HeartbeatEventWriter heartbeatWriter;
    private final BatchAlertConsumer batchAlertConsumer;
    private final AsyncAlertExecutor alertExecutor;

    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        for (Map.Entry<String, Integer> e : props.getConsumer().getConcurrency().entrySet()) {
            String topic = e.getKey();
            int n = e.getValue();
            if (n < 1) n = 1;
            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
            executors.put(topic, exec);
            for (int i = 0; i < n; i++) {
                exec.submit(() -> consumeLoop(topic));
            }
            log.info("[kxj: InflightConsumer topic={} 启动 {} 个虚拟线程]", topic, n);
        }
    }

    @PreDestroy
    void shutdown() {
        for (ExecutorService exec : executors.values()) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) exec.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exec.shutdownNow();
            }
        }
    }

    private void consumeLoop(String topic) {
        int maxBatch = props.getConsumer().getPollMaxBatch();
        long waitMs = props.getConsumer().getPollWaitMs();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Partition[] arr = inflightQueue.partitionsOf(topic);
                if (arr == null || arr.length == 0) {
                    Thread.sleep(waitMs);
                    continue;
                }
                Partition p = arr[ThreadLocalRandom.current().nextInt(arr.length)];
                List<Object> events = p.drain(maxBatch);
                if (events.isEmpty()) {
                    Thread.sleep(waitMs);
                    continue;
                }
                processBatch(topic, p.partitionId(), events);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("[kxj: InflightConsumer 处理失败 - topic={}, error={}]", topic, e.getMessage(), e);
            }
        }
    }

    private void processBatch(String topic, int partitionId, List<Object> events) {
        inflightQueue.metrics().recordBatchSize(topic, partitionId, events.size());

        try {
            switch (topic) {
                case InflightProducerBridge.TOPIC_METRICS -> {
                    int n = metricWriter.write(events);
                    if (n > 0) {
                        inflightQueue.metrics().drained(topic, partitionId, n);
                        log.info("[kxj: InflightConsumer metrics 写 InfluxDB - size={}]", n);
                    }
                    // 告警评估异步 fire-and-forget: AlertEngine.process 是无状态且由 AsyncAlertExecutor
                    // 内部虚拟线程池执行, 不阻塞 partition 处理下一批; 评估丢失风险同 Kafka 时代
                    // (group 关闭时未 ack 的 offset 也会丢)
                    submitAlertAsync(topic, () -> batchAlertConsumer.evaluate(events));
                }
                case InflightProducerBridge.TOPIC_LOGS -> {
                    List<LogEvent> kept = logWriter.write(events);
                    if (!kept.isEmpty()) {
                        inflightQueue.metrics().drained(topic, partitionId, kept.size());
                        log.info("[kxj: InflightConsumer logs 写 InfluxDB - size={}]", kept.size());
                        // 修复: 迁移丢日志告警评估。沿用旧 BatchLogConsumer.alertCandidates 语义,
                        // 只对 dedup 后保留的事件做 log_keyword / log_new_pattern 实时评估
                        submitAlertAsync(topic, () -> {
                            for (LogEvent e : kept) {
                                alertExecutor.submit(e);
                            }
                        });
                    }
                }
                case InflightProducerBridge.TOPIC_HEARTBEAT -> {
                    var r = heartbeatWriter.write(events);
                    inflightQueue.metrics().drained(topic, partitionId, r.processed());
                    log.info("[kxj: InflightConsumer heartbeats 写 PG - total={}, processed={}, failed={}, notInDb={}]",
                        events.size(), r.processed(), r.failed(), r.notInDb());
                }
                default -> log.warn("[kxj: InflightConsumer 未知 topic={}]", topic);
            }
        } catch (Exception e) {
            log.error("[kxj: InflightConsumer 写入失败 - topic={}, size={}, error={}]",
                topic, events.size(), e.getMessage());
            inflightQueue.metrics().rejected(topic, partitionId);
        }
    }

    /**
     * 提交告警评估到该 topic 的虚拟线程 executor, fire-and-forget 不阻塞主线程。
     * executor 可能在 @PreDestroy 阶段已 shutdown, 此时退化为同步执行, 避免任务被丢。
     */
    private void submitAlertAsync(String topic, Runnable task) {
        ExecutorService exec = executors.get(topic);
        if (exec == null || exec.isShutdown()) {
            task.run();
            return;
        }
        try {
            exec.execute(task);
        } catch (Exception e) {
            log.warn("[kxj: InflightConsumer 告警评估提交失败, 降级同步 - topic={}, error={}]",
                topic, e.getMessage());
            task.run();
        }
    }
}
