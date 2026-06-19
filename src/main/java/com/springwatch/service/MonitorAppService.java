package com.springwatch.service;

import com.springwatch.collector.OtelConfigGenerator;
import com.springwatch.collector.schedule.CollectScheduleRegistry;
import com.springwatch.model.dto.AppRegisterRequest;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.MonitorAppRepository;
import com.springwatch.util.SnowFlakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorAppService {

    private final MonitorAppRepository monitorAppRepository;
    private final CollectScheduleRegistry collectScheduleRegistry;
    private final OtelConfigGenerator otelConfigGenerator;

    public List<MonitorApp> listAll() {
        return monitorAppRepository.findAll();
    }

    public List<MonitorApp> listActive() {
        return monitorAppRepository.findAll().stream()
                .filter(a -> "active".equalsIgnoreCase(a.getStatus()))
                .toList();
    }

    public Optional<MonitorApp> findById(Long id) {
        return monitorAppRepository.findById(id);
    }

    public Optional<MonitorApp> findByAppid(Long appid) {
        return monitorAppRepository.findByAppid(appid);
    }

    @Transactional
    public MonitorApp create(AppRegisterRequest req) {
        long appid = SnowFlakeIdGenerator.generateId();
        MonitorApp app = MonitorApp.builder()
                .appid(appid)
                .appName(req.getAppName())
                .endpoint(req.getEndpoint())
                .metricsPort(req.getMetricsPort() == null ? 9464 : req.getMetricsPort())
                .appType(req.getAppType() == null ? "springboot" : req.getAppType())
                .scrapeInterval(req.getScrapeInterval() == null ? 15 : req.getScrapeInterval())
                .scheduleType(req.getScheduleType() == null ? "INTERVAL" : req.getScheduleType())
                .cronExpression(req.getCronExpression())
                .labels(req.getLabels())
                .status("active")
                .build();
        MonitorApp saved = monitorAppRepository.save(app);
        try {
            collectScheduleRegistry.upsert(saved);
        } catch (Exception e) {
            log.warn("[spring-watch: 应用注册调度失败 - appid={}, error={}]", appid, e.getMessage());
        }
        log.info("[spring-watch: 应用注册成功 - appid={}, name={}, endpoint={}]",
                appid, saved.getAppName(), saved.getEndpoint());
        return saved;
    }

    @Transactional
    public MonitorApp update(Long id, AppRegisterRequest req) {
        MonitorApp app = monitorAppRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: " + id));
        if (req.getAppName() != null) app.setAppName(req.getAppName());
        if (req.getEndpoint() != null) app.setEndpoint(req.getEndpoint());
        if (req.getMetricsPort() != null) app.setMetricsPort(req.getMetricsPort());
        if (req.getScheduleType() != null) app.setScheduleType(req.getScheduleType());
        if (req.getScrapeInterval() != null) app.setScrapeInterval(req.getScrapeInterval());
        if (req.getCronExpression() != null) app.setCronExpression(req.getCronExpression());
        if (req.getLabels() != null) app.setLabels(req.getLabels());
        if (req.getAppType() != null) app.setAppType(req.getAppType());
        MonitorApp saved = monitorAppRepository.save(app);
        try {
            collectScheduleRegistry.upsert(saved);
        } catch (Exception e) {
            log.warn("[spring-watch: 调度刷新失败 - appid={}, error={}]", saved.getAppid(), e.getMessage());
        }
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        MonitorApp app = monitorAppRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: " + id));
        try {
            collectScheduleRegistry.cancel(app.getAppid());
        } catch (Exception e) {
            log.warn("[spring-watch: 调度注销失败 - appid={}, error={}]", app.getAppid(), e.getMessage());
        }
        monitorAppRepository.deleteById(id);
    }

    @Transactional
    public MonitorApp pause(Long id) {
        MonitorApp app = monitorAppRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: " + id));
        app.setStatus("paused");
        MonitorApp saved = monitorAppRepository.save(app);
        try {
            collectScheduleRegistry.cancel(app.getAppid());
        } catch (Exception e) {
            log.warn("[spring-watch: 暂停调度失败 - appid={}, error={}]", app.getAppid(), e.getMessage());
        }
        return saved;
    }

    @Transactional
    public MonitorApp resume(Long id) {
        MonitorApp app = monitorAppRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: " + id));
        app.setStatus("active");
        MonitorApp saved = monitorAppRepository.save(app);
        try {
            collectScheduleRegistry.upsert(saved);
        } catch (Exception e) {
            log.warn("[spring-watch: 恢复调度失败 - appid={}, error={}]", app.getAppid(), e.getMessage());
        }
        return saved;
    }

    public Map<String, Object> generateOtelConfig(Long id) {
        MonitorApp app = monitorAppRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: " + id));
        int port = app.getMetricsPort() == null ? 9464 : app.getMetricsPort();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appid", app.getAppid());
        out.put("appName", app.getAppName());
        out.put("endpoint", app.getEndpoint());
        out.put("metricsPort", port);
        out.put("env", otelConfigGenerator.generateOtelConfig(app.getAppid(), port));
        out.put("command", otelConfigGenerator.generateOtelAgentCommand(
                "/path/to/opentelemetry-javaagent.jar", app.getAppid(), port));
        return out;
    }

    public void touchHeartbeat(Long id) {
        monitorAppRepository.findById(id).ifPresent(a -> {
            a.setLastHeartbeat(Instant.now());
            monitorAppRepository.save(a);
        });
    }
}
