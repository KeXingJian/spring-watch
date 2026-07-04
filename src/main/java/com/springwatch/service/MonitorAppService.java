package com.springwatch.service;

import com.springwatch.collector.OtelConfigGenerator;
import com.springwatch.collector.schedule.CollectScheduleRegistry;
import com.springwatch.model.dto.AppRegisterRequest;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.MonitorAppRepository;
import com.springwatch.util.SnowFlakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorAppService {

    private final MonitorAppRepository monitorAppRepository;
    private final CollectScheduleRegistry collectScheduleRegistry;
    private final OtelConfigGenerator otelConfigGenerator;


    public Page<MonitorApp> listAll(Pageable pageable) {
        return monitorAppRepository.findAll(pageable);
    }

    public Page<MonitorApp> listActive(Pageable pageable) {
        return monitorAppRepository.findByStatusIgnoreCase("active", pageable);
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
            log.warn("[kxj: 应用注册调度失败 - appid={}, error={}]", appid, e.getMessage());
        }
        log.info("[kxj: 应用注册成功 - appid={}, name={}, endpoint={}]",
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
        MonitorApp saved = monitorAppRepository.save(app);
        try {
            collectScheduleRegistry.upsert(saved);
        } catch (Exception e) {
            log.warn("[kxj: 调度刷新失败 - appid={}, error={}]", saved.getAppid(), e.getMessage());
        }
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        MonitorApp app = monitorAppRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: " + id));
        // 自监控占位 app(endpoint = self://infra)由 InfrastructureAlertScheduler 启动时
        // ensureSelfApp() 创建,承载基础设施告警的归属。误删后下次启动会重建,但告警历史
        // 链会断(规则里有 app_id 外键,历史命中不到),所以这里硬挡一下。
        if ("self://infra".equals(app.getEndpoint())) {
            throw new IllegalArgumentException(
                    "自监控占位应用不可删除 (id=" + id + ", name=" + app.getAppName()
                            + "),如需关闭请在 spring-watch.infra-alerts.enabled=false 中禁用");
        }
        try {
            collectScheduleRegistry.cancel(app.getAppid());
        } catch (Exception e) {
            log.warn("[kxj: 调度注销失败 - appid={}, error={}]", app.getAppid(), e.getMessage());
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
            log.warn("[kxj: 暂停调度失败 - appid={}, error={}]", app.getAppid(), e.getMessage());
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
            log.warn("[kxj: 恢复调度失败 - appid={}, error={}]", saved.getAppid(), e.getMessage());
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


}
