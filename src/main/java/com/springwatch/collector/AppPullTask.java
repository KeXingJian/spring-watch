package com.springwatch.collector;

import com.springwatch.collector.schedule.AppScheduleProperties;
import com.springwatch.collector.schedule.GlobalHealthMonitor;
import com.springwatch.collector.schedule.HostCircuitBreaker;
import com.springwatch.collector.schedule.HostLatencyTracker;
import com.springwatch.collector.schedule.PullRetryQueue;
import com.springwatch.collector.schedule.RetryPull;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.entity.MonitorStatus;
import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.repository.MonitorAppRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppPullTask {

    private final MonitorAppRepository monitorAppRepository;
    private final AgentMetricsCollector agentMetricsCollector;
    private final AgentLogCollector agentLogCollector;
    private final KafkaProducerBridge kafkaProducerBridge;
    private final PullRetryQueue pullRetryQueue;
    private final HostCircuitBreaker hostCircuitBreaker;
    private final HostLatencyTracker hostLatencyTracker;
    private final GlobalHealthMonitor globalHealthMonitor;
    private final AppScheduleProperties properties;
    private final MeterRegistry meterRegistry;

    private Timer pullDurationTimer;
    private Counter unreachableCounter;

    private static final String[] BUCKET_LABELS = {"1s", "2s", "3s", "4s", "5s", "6s", "7s", "8s", "9s", "10s", ">10s"};
    private final AtomicLong[] bucketCounters = new AtomicLong[BUCKET_LABELS.length];

    @PostConstruct
    void registerMeters() {
        this.pullDurationTimer = Timer.builder("spring.watch.collector.pull.duration")
                .description("采集任务耗时直方图(1s间隔: 1-10s + >10s; 不可达见 unreachable 计数器)")
                .serviceLevelObjectives(
                        Duration.ofSeconds(1),  Duration.ofSeconds(2),  Duration.ofSeconds(3),
                        Duration.ofSeconds(4),  Duration.ofSeconds(5),  Duration.ofSeconds(6),
                        Duration.ofSeconds(7),  Duration.ofSeconds(8),  Duration.ofSeconds(9),
                        Duration.ofSeconds(10))
                .register(meterRegistry);
        this.unreachableCounter = Counter.builder("spring.watch.collector.pull.unreachable")
                .description("采集任务不可达次数(对应耗时直方图12s档)")
                .register(meterRegistry);
        for (int i = 0; i < bucketCounters.length; i++) {
            bucketCounters[i] = new AtomicLong(0);
        }
    }

    public void run(Long appid) {
        long start = System.nanoTime();
        boolean success = false;

        MonitorApp app = monitorAppRepository.findByAppid(appid).orElse(null);
        if (app == null) {
            log.warn("[kxj: AppPullTask.run跳过 - appid={} 在DB中不存在]", appid);
            return;
        }
        if (MonitorStatus.isPaused(app.getStatus())) {
            log.debug("[kxj: AppPullTask.run跳过 - appid={}, status=paused(用户暂停), 调度任务保持运行不取消]", appid);
            return;
        }

        if (pullRetryQueue.hasInQueue(appid)) {
            log.debug("[kxj: AppPullTask.run跳过 - appid={}, 重试队列以存在]", appid);
            return;
        }

        String host = extractHost(app);
        log.info("[kxj: AppPullTask开始拉取 - appid={}, app={}, host={}, endpoint={}, metricsPort={}, currentStatus={}]",
                appid, app.getAppName(), host, app.getEndpoint(), app.getMetricsPort(), app.getStatus());

        if (!hostCircuitBreaker.tryAcquire(host)) {
            HostCircuitBreaker.State st = hostCircuitBreaker.stateOf(host);
            log.warn("[kxj: 熔断器快速失败 - appid={}, host={}, state={}, coolDownMs={}, 入重投队列, 不消耗限流permit]",
                    appid, host, st, hostCircuitBreaker.coolDownMsOf(host));
            pullRetryQueue.enqueue(new RetryPull(appid, host, 1, Instant.now()));
            return;
        }

        int timeoutMs = hostLatencyTracker.adaptiveTimeoutMs(host);
        try {
            HostCircuitBreaker.Outcome outcome = doHeavyWork(appid, timeoutMs);
            if (outcome == HostCircuitBreaker.Outcome.TIMEOUT || outcome == HostCircuitBreaker.Outcome.ERROR) {
                pullRetryQueue.enqueue(new RetryPull(appid, host, 1, Instant.now()));
                return;
            }
            if (outcome == HostCircuitBreaker.Outcome.SLOW) {
                log.debug("[kxj: 拉取SLOW但成功(已计入熔断器滑窗) - appid={}, host={}]", appid, host);
            }
            log.debug("[kxj: 可达性探测通过(合并到指标拉取) - appid={}, host={}]", appid, host);
            sendHeartbeat(app);
            success = true;
        } catch (Exception e) {
            log.warn("[kxj: 拉取异常 - appid={}, app={}, error={}]", appid, app.getAppName(), e.getMessage(), e);
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            recordCost(start, appid, app.getAppName());
            globalHealthMonitor.recordPull(costMs, success);
        }
    }

    public void recordCost(long startNs, Long appid, String appName) {
        long costMs = (System.nanoTime() - startNs) / 1_000_000L;
        pullDurationTimer.record(Duration.ofMillis(costMs));
        bucketCounters[bucketIndex(costMs)].incrementAndGet();
        if (costMs > 5_000L) {
            log.warn("[kxj: 拉取耗时过长 - appid={}, app={}, costMs={}]", appid, appName, costMs);
        } else {
            log.debug("[kxj: 拉取完成耗时 - appid={}, costMs={}]", appid, costMs);
        }
    }

    private static int bucketIndex(long costMs) {
        if (costMs <= 1_000L) return 0;
        if (costMs <= 2_000L) return 1;
        if (costMs <= 3_000L) return 2;
        if (costMs <= 4_000L) return 3;
        if (costMs <= 5_000L) return 4;
        if (costMs <= 6_000L) return 5;
        if (costMs <= 7_000L) return 6;
        if (costMs <= 8_000L) return 7;
        if (costMs <= 9_000L) return 8;
        if (costMs <= 10_000L) return 9;
        return 10;
    }


    public HostCircuitBreaker.Outcome doHeavyWork(Long appid, int timeoutMs) {
        MonitorApp app = monitorAppRepository.findByAppid(appid).orElse(null);
        if (app == null) {
            log.warn("[kxj: 重投执行跳过 - appid={} 已删除]", appid);
            return HostCircuitBreaker.Outcome.ERROR;
        }
        if (MonitorStatus.isPaused(app.getStatus())) {
            log.debug("[kxj: 重投执行跳过 - appid={} 已暂停]", appid);
            return HostCircuitBreaker.Outcome.ERROR;
        }

        String host = extractHost(app);
        Instant now = Instant.now();
        Integer metricsPort = app.getMetricsPort() != null ? app.getMetricsPort() : 9464;
        AgentMetricsCollector.MonitorTarget target = new AgentMetricsCollector.MonitorTarget(
                app.getAppid(), app.getAppName(), app.getEndpoint(), metricsPort);

        AgentMetricsCollector.Result m = agentMetricsCollector.collect(target, timeoutMs);
        HostCircuitBreaker.Outcome outcome = classifyOutcome(m);
        hostLatencyTracker.record(host, m.latencyMs());
        hostCircuitBreaker.recordOutcome(host, outcome, m.latencyMs());

        if (!m.ok()) {
            log.debug("[kxj: 指标拉取失败, 中止后续日志/状态更新 - appid={}, outcome={}, latencyMs={}]",
                    appid, outcome, m.latencyMs());
            return outcome;
        }

        Instant since = app.getLastLogPullTime() != null ? app.getLastLogPullTime() : now.minusSeconds(3600);

        Instant latest = agentLogCollector.collect(app.getAppid(), app.getAppName(), app.getEndpoint(), since);
        if (latest.isAfter(since)) {
            app.setLastLogPullTime(latest);
            app.setUpdatedAt(now);
            monitorAppRepository.save(app);
        } else {
            log.trace("[kxj: 日志无新进展 - appid={}, since={}]", appid, since);
        }

        return outcome;
    }

    private HostCircuitBreaker.Outcome classifyOutcome(AgentMetricsCollector.Result m) {
        if (m.ok()) {
            long slowThreshold = properties.getCircuitBreaker().getSlowThresholdMs();
            return m.latencyMs() > slowThreshold ? HostCircuitBreaker.Outcome.SLOW : HostCircuitBreaker.Outcome.SUCCESS;
        }
        if (m.error() != null && m.error().startsWith("timeout")) {
            return HostCircuitBreaker.Outcome.TIMEOUT;
        }
        return HostCircuitBreaker.Outcome.ERROR;
    }

    private String extractHost(MonitorApp app) {
        String endpoint = app.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            log.trace("[kxj: endpoint为空 - appid={}, fallback=localhost]", app.getAppid());
            return "localhost";
        }
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost() != null ? uri.getHost() : "localhost";
            log.trace("[kxj: 解析endpoint - appid={}, endpoint={} -> host={}",
                    app.getAppid(), endpoint, host);
            return host;
        } catch (Exception e) {
            log.warn("[kxj: 解析endpoint异常 - appid={}, endpoint={}, error={}, fallback=localhost]",
                    app.getAppid(), endpoint, e.getMessage());
            return "localhost";
        }
    }

    public void sendHeartbeat(MonitorApp app) {
        HeartbeatEvent heartbeat = HeartbeatEvent.builder()
                .appid(app.getAppid())
                .ip(extractHost(app))
                .agentVersion("java-agent")
                .timestamp(Instant.now())
                .build();
        kafkaProducerBridge.sendHeartbeat(heartbeat);
    }



    public Map<String, Object> snapshotPullHistogram() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (pullDurationTimer == null) {
            result.put("total", 0L);
            result.put("unreachable", 0L);
            result.put("buckets", List.of());
            return result;
        }
        long unreachable = (long) unreachableCounter.count();
        long total = 0L;
        List<Map<String, Object>> buckets = new ArrayList<>(BUCKET_LABELS.length + 1);
        for (int i = 0; i < bucketCounters.length; i++) {
            long count = bucketCounters[i].get();
            total += count;
            buckets.add(Map.of("label", BUCKET_LABELS[i], "count", count));
        }
        buckets.add(Map.of("label", "unreachable", "count", unreachable));
        result.put("total", total);
        result.put("unreachable", unreachable);
        result.put("buckets", buckets);
        return result;
    }
}
