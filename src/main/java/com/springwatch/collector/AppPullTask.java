package com.springwatch.collector;

import com.springwatch.collector.schedule.CollectorThrottler;
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
    private final CollectorThrottler hostThrottler;
    private final PullRetryQueue pullRetryQueue;
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

        MonitorApp app = monitorAppRepository.findByAppid(appid).orElse(null);
        if (app == null) {
            log.warn("[spring-watch: AppPullTask.run跳过 - appid={} 在DB中不存在]", appid);
            return;
        }
        if (MonitorStatus.isPaused(app.getStatus())) {
            log.debug("[spring-watch: AppPullTask.run跳过 - appid={}, status=paused(用户暂停), 调度任务保持运行不取消]", appid);
            return;
        }

        String host = extractHost(app);
        log.info("[spring-watch: AppPullTask开始拉取 - appid={}, app={}, host={}, endpoint={}, metricsPort={}, currentStatus={}]",
                appid, app.getAppName(), host, app.getEndpoint(), app.getMetricsPort(), app.getStatus());

        if (!hostThrottler.tryAcquire(host, 0L)) {
            log.warn("[spring-watch: 拉取被限流 - appid={}, host={}, 心跳已发, 入重投队列]",
                    appid, host);
            pullRetryQueue.enqueue(new RetryPull(appid, host, 0, Instant.now()));
//            recordCost(start, appid, app.getAppName());
            return;
        }

        try {
            boolean reachable = doHeavyWork(appid);
            if (!reachable) {
                markUnreachable(app);
                log.debug("[spring-watch: AppPullTask.run结束 - appid={}, reason=unreachable, totalCostMs={}]",
                        appid, (System.nanoTime() - start) / 1_000_000L);
                return;
            }
            log.debug("[spring-watch: 可达性探测通过(合并到指标拉取) - appid={}, host={}]", appid, host);
            sendHeartbeat(app);
        } catch (Exception e) {
            log.warn("[spring-watch: 拉取异常 - appid={}, app={}, error={}]", appid, app.getAppName(), e.getMessage(), e);
        } finally {
            hostThrottler.release(host);
            recordCost(start, appid, app.getAppName());
        }
    }

    public void recordCost(long startNs, Long appid, String appName) {
        long costMs = (System.nanoTime() - startNs) / 1_000_000L;
        pullDurationTimer.record(Duration.ofMillis(costMs));
        bucketCounters[bucketIndex(costMs)].incrementAndGet();
        if (costMs > 5_000L) {
            log.warn("[spring-watch: 拉取耗时过长 - appid={}, app={}, costMs={}]", appid, appName, costMs);
        } else {
            log.debug("[spring-watch: 拉取完成耗时 - appid={}, costMs={}]", appid, costMs);
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

    public void markUnreachable(MonitorApp app) {
        if (app == null) return;
        unreachableCounter.increment();
        if (!MonitorStatus.isPaused(app.getStatus())) {
            markInactive(app);
        }
    }

    public boolean doHeavyWork(Long appid) {
        MonitorApp app = monitorAppRepository.findByAppid(appid).orElse(null);
        if (app == null) {
            log.warn("[spring-watch: 重投执行跳过 - appid={} 已删除]", appid);
            return false;
        }
        if (MonitorStatus.isPaused(app.getStatus())) {
            log.debug("[spring-watch: 重投执行跳过 - appid={} 已暂停]", appid);
            return false;
        }

        Instant now = Instant.now();
        Integer metricsPort = app.getMetricsPort() != null ? app.getMetricsPort() : 9464;
        AgentMetricsCollector.MonitorTarget target = new AgentMetricsCollector.MonitorTarget(
                app.getAppid(), app.getAppName(), app.getEndpoint(), metricsPort);

        log.debug("[spring-watch: 拉指标 - appid={}, url=http://{}:{}/metrics",
                appid, extractHost(app), metricsPort);
        boolean reachable = agentMetricsCollector.collect(target);
        if (!reachable) {
            log.debug("[spring-watch: 指标拉取失败, 中止后续日志/状态更新 - appid={}]", appid);
            return false;
        }
        log.debug("[spring-watch: 指标拉取完成 - appid={}]", appid);

        Instant since = app.getLastLogPullTime() != null ? app.getLastLogPullTime() : now.minusSeconds(3600);
        log.debug("[spring-watch: 拉日志 - appid={}, since={}]", appid, since);
        Instant latest = agentLogCollector.collect(app.getAppid(), app.getAppName(), app.getEndpoint(), since);
        if (latest.isAfter(since)) {
            log.info("[spring-watch: 日志有新进展 - appid={}, since={} -> latest={}]", appid, since, latest);
            app.setLastLogPullTime(latest);
            app.setUpdatedAt(now);
            monitorAppRepository.save(app);
        } else {
            log.trace("[spring-watch: 日志无新进展 - appid={}, since={}]", appid, since);
        }

        if (!MonitorStatus.isActive(app.getStatus()) && !MonitorStatus.isPaused(app.getStatus())) {
            app.setStatus(MonitorStatus.ACTIVE);
            app.setUpdatedAt(now);
            monitorAppRepository.save(app);
            log.info("[spring-watch: Agent复活(重投) - appid={}, app={}, {} -> active]", appid, app.getAppName(), app.getStatus());
        }
        return true;
    }

    private String extractHost(MonitorApp app) {
        String endpoint = app.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            log.trace("[spring-watch: endpoint为空 - appid={}, fallback=localhost]", app.getAppid());
            return "localhost";
        }
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost() != null ? uri.getHost() : "localhost";
            log.trace("[spring-watch: 解析endpoint - appid={}, endpoint={} -> host={}",
                    app.getAppid(), endpoint, host);
            return host;
        } catch (Exception e) {
            log.warn("[spring-watch: 解析endpoint异常 - appid={}, endpoint={}, error={}, fallback=localhost]",
                    app.getAppid(), endpoint, e.getMessage());
            return "localhost";
        }
    }

    private void sendHeartbeat(MonitorApp app) {
        HeartbeatEvent heartbeat = HeartbeatEvent.builder()
                .appid(app.getAppid())
                .ip(extractHost(app))
                .agentVersion("java-agent")
                .timestamp(Instant.now())
                .build();
        kafkaProducerBridge.sendHeartbeat(heartbeat);
    }

    private void markInactive(MonitorApp app) {
        log.warn("[spring-watch: Agent失活 - appid={}, app={}, reason={}]",
                app.getAppid(), app.getAppName(), "agent端口不可达");
        app.setStatus(MonitorStatus.INACTIVE);
        app.setUpdatedAt(Instant.now());
        monitorAppRepository.save(app);
    }

    /**
     * 采集耗时直方图当前快照 - 给自监控视图一次性拉取。
     * 12 个分桶: 1s / 2s / ... / 10s / >10s(11s 档,含所有超过 10s 的样本) / 不可达(12s 档)。
     * count 是各桶的"增量"计数(非 Prometheus 累积语义),前端直接画柱图即可。
     *
     * kxj: 之前用 pullDurationTimer.takeSnapshot().histogramCounts() 拿桶,
     *      SimpleMeterRegistry 下 SimpleTimer 内部对 SLO 不生成 bucket(histogramCounts() 返回空),
     *      原代码 prev=0、overflow=total-0=total,所有样本被算进 >10s 桶。
     *      实际多数拉取 1-3s,故 5_000ms 阈值的 warn 从来不出现 → "采集统计不符合"现象。
     *      改为 AtomicLong 自维护分桶,不依赖 registry 实现。
     */
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
