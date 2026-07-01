package com.springwatch.collector;

import com.springwatch.collector.schedule.HostThrottler;
import com.springwatch.collector.schedule.PullRetryQueue;
import com.springwatch.collector.schedule.RetryPull;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.entity.MonitorStatus;
import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.repository.MonitorAppRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppPullTask {

    private final MonitorAppRepository monitorAppRepository;
    private final AgentMetricsCollector agentMetricsCollector;
    private final AgentLogCollector agentLogCollector;
    private final KafkaProducerBridge kafkaProducerBridge;
    private final HostThrottler hostThrottler;
    private final PullRetryQueue pullRetryQueue;
    private final AgentHttpClient agentHttpClient;
    private final MeterRegistry meterRegistry;

    private Counter slowPullCounter;

    @PostConstruct
    void registerMeters() {
        this.slowPullCounter = Counter.builder("spring.watch.collector.pull.slow")
                .description("单次拉取耗时超过 5s 的次数")
                .register(meterRegistry);
    }

    public void run(Long appid) {
        long start = System.nanoTime();
        log.debug("[spring-watch: AppPullTask.run开始 - appid={}]", appid);
        MonitorApp app = monitorAppRepository.findByAppid(appid).orElse(null);
        if (app == null) {
            log.warn("[spring-watch: AppPullTask.run跳过 - appid={} 在DB中不存在]", appid);
            return;
        }
        if (MonitorStatus.isPaused(app.getStatus())) {
            log.debug("[spring-watch: AppPullTask.run跳过 - appid={}, status=paused(用户暂停), 调度任务保持运行不取消]",
                    appid);
            return;
        }

        String host = extractHost(app);
        log.info("[spring-watch: AppPullTask开始拉取 - appid={}, app={}, host={}, endpoint={}, metricsPort={}, currentStatus={}]",
                appid, app.getAppName(), host, app.getEndpoint(), app.getMetricsPort(), app.getStatus());

        if (!hostThrottler.tryAcquire(host, 0L)) {
            log.warn("[spring-watch: 拉取被限流 - appid={}, host={}, 心跳已发, 入重投队列(借鉴HertzBeat降级重投)]",
                    appid, host);
            pullRetryQueue.enqueue(new RetryPull(appid, host, 0, Instant.now()));
            log.debug("[spring-watch: AppPullTask.run结束 - appid={}, reason=throttled-and-queued, totalCostMs={}]",
                    appid, (System.nanoTime() - start) / 1_000_000L);
            return;
        }

        if (!isReachable(app)) {
            markInactive(app);
            log.debug("[spring-watch: AppPullTask.run结束 - appid={}, reason=unreachable, totalCostMs={}]",
                    appid, (System.nanoTime() - start) / 1_000_000L);
            return;
        }
        log.debug("[spring-watch: 可达性探测通过 - appid={}, host={}]", appid, host);

        log.debug("[spring-watch: 发送心跳 - appid={}, ip={}]", appid, host);
        sendHeartbeat(app);


        try {
            doHeavyWork(appid);
        } catch (Exception e) {
            log.warn("[spring-watch: 拉取异常 - appid={}, app={}, error={}]", appid, app.getAppName(), e.getMessage(), e);
        } finally {
            hostThrottler.release(host);
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            if (costMs > 5_000L) {
                slowPullCounter.increment();
                log.warn("[spring-watch: 拉取耗时过长 - appid={}, app={}, costMs={}]", appid, app.getAppName(), costMs);
            } else {
                log.debug("[spring-watch: 拉取完成耗时 - appid={}, costMs={}]", appid, costMs);
            }
        }
    }

    public void doHeavyWork(Long appid) {
        MonitorApp app = monitorAppRepository.findByAppid(appid).orElse(null);
        if (app == null) {
            log.warn("[spring-watch: 重投执行跳过 - appid={} 已删除]", appid);
            return;
        }
        if (MonitorStatus.isPaused(app.getStatus())) {
            log.debug("[spring-watch: 重投执行跳过 - appid={} 已暂停]", appid);
            return;
        }

        Instant now = Instant.now();
        Integer metricsPort = app.getMetricsPort() != null ? app.getMetricsPort() : 9464;
        AgentMetricsCollector.MonitorTarget target = new AgentMetricsCollector.MonitorTarget(
                app.getAppid(), app.getAppName(), app.getEndpoint(), metricsPort);

        log.debug("[spring-watch: 拉指标 - appid={}, url=http://{}:{}/metrics",
                appid, extractHost(app), metricsPort);
        agentMetricsCollector.collect(target);
        log.debug("[spring-watch: 指标拉取完成 - appid={}]", appid);

        Instant since = app.getLastLogPullTime() != null
                ? app.getLastLogPullTime()
                : now.minusSeconds(3600);
        log.debug("[spring-watch: 拉日志 - appid={}, since={}]", appid, since);
        Instant latest = agentLogCollector.collect(app.getAppid(), app.getAppName(), app.getEndpoint(), since);
        if (latest.isAfter(since)) {
            log.info("[spring-watch: 日志有新进展 - appid={}, since={} -> latest={}]",
                    appid, since, latest);
            app.setLastLogPullTime(latest);
            app.setUpdatedAt(now);
            monitorAppRepository.save(app);
        } else {
            log.trace("[spring-watch: 日志无新进展 - appid={}, since={}]", appid, since);
        }

        if (!MonitorStatus.isActive(app.getStatus())
                && !MonitorStatus.isPaused(app.getStatus())) {
            app.setStatus(MonitorStatus.ACTIVE);
            app.setUpdatedAt(now);
            monitorAppRepository.save(app);
            log.info("[spring-watch: Agent复活(重投) - appid={}, app={}, {} -> active]",
                    appid, app.getAppName(), app.getStatus());
        }
    }

    private boolean isReachable(MonitorApp app) {
        int port = app.getMetricsPort() != null ? app.getMetricsPort() : 9464;
        String host = extractHost(app);
        String url = "http://" + host + ":" + port + "/metrics";
        boolean ok = agentHttpClient.reachable(url, 5000);
        log.debug("[kxj: 可达性探测走 HEAD - appid={}, url={}, reachable={}, RTT缩短至1RTT]",
                app.getAppid(), url, ok);
        return ok;
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
}
