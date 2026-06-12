package com.springwatch.collector;

import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.repository.MonitorAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectorScheduler {

    private final MonitorAppRepository monitorAppRepository;
    private final AgentMetricsCollector agentMetricsCollector;
    private final AgentLogCollector agentLogCollector;
    private final KafkaProducerBridge kafkaProducerBridge;

    @Scheduled(fixedDelayString = "${spring-watch.collector.interval:15000}")
    public void pullAgentData() {
        List<MonitorApp> activeApps = monitorAppRepository.findAll();
        if (activeApps.isEmpty()) {
            log.debug("[spring-watch: Agent拉取调度 - 无应用, 跳过本轮]");
            return;
        }
        log.info("[spring-watch: Agent拉取调度开始 - 应用数={}]", activeApps.size());

        Instant now = Instant.now();
        for (MonitorApp app : activeApps) {
            try {
                Integer metricsPort = app.getMetricsPort() != null ? app.getMetricsPort() : 9464;
                AgentMetricsCollector.MonitorTarget target =
                        new AgentMetricsCollector.MonitorTarget(app.getAppName(), app.getEndpoint(), metricsPort);

                if (isReachable(target)) {
                    agentMetricsCollector.collect(target);
                    sendHeartbeat(app);

                    Instant since = app.getLastLogPullTime() != null
                            ? app.getLastLogPullTime()
                            : now.minusSeconds(3600);
                    Instant latest = agentLogCollector.collect(app.getAppName(), app.getEndpoint(), since);
                    if (latest.isAfter(since)) {
                        app.setLastLogPullTime(latest);
                        app.setUpdatedAt(now);
                        monitorAppRepository.save(app);
                    }

                    if (!"active".equals(app.getStatus())) {
                        app.setStatus("active");
                        app.setUpdatedAt(now);
                        monitorAppRepository.save(app);
                        log.info("[spring-watch: Agent复活 - app={}]", app.getAppName());
                    }
                } else {
                    markInactive(app, "agent端口不可达");
                }
            } catch (Exception e) {
                log.warn("[spring-watch: 调度异常 - app={}, error={}]", app.getAppName(), e.getMessage());
            }
        }
    }

    private boolean isReachable(AgentMetricsCollector.MonitorTarget target) {
        String url = "http://" + extractHost(target.endpoint()) + ":" + target.metricsPort() + "/metrics";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            log.debug("[spring-watch: Agent端口不可达 - app={}, url={}, error={}]",
                    target.appName(), url, e.getMessage());
            return false;
        }
    }

    private String extractHost(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "localhost";
        }
        try {
            URI uri = URI.create(endpoint);
            return uri.getHost() != null ? uri.getHost() : "localhost";
        } catch (Exception e) {
            return "localhost";
        }
    }

    private void sendHeartbeat(MonitorApp app) {
        HeartbeatEvent heartbeat = HeartbeatEvent.builder()
                .appName(app.getAppName())
                .ip(extractHost(app.getEndpoint()))
                .agentVersion("java-agent")
                .timestamp(Instant.now())
                .build();
        kafkaProducerBridge.sendHeartbeat(heartbeat);
    }

    private void markInactive(MonitorApp app, String reason) {
        log.warn("[spring-watch: Agent失活 - app={}, reason={}]", app.getAppName(), reason);
        app.setStatus("inactive");
        app.setUpdatedAt(Instant.now());
        monitorAppRepository.save(app);
    }
}
