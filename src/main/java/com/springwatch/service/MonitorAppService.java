package com.springwatch.service;

import com.springwatch.collector.OtelConfigGenerator;
import com.springwatch.model.dto.AppRegisterRequest;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.MonitorAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorAppService {

    private final MonitorAppRepository monitorAppRepository;
    private final OtelConfigGenerator otelConfigGenerator;

    @Transactional
    public MonitorApp register(AppRegisterRequest request) {
        if (monitorAppRepository.existsByAppName(request.getAppName())) {
            throw new IllegalArgumentException("应用已存在: " + request.getAppName());
        }

        MonitorApp app = MonitorApp.builder()
                .appName(request.getAppName())
                .endpoint(request.getEndpoint())
                .appType(request.getAppType())
                .scrapeInterval(request.getScrapeInterval())
                .labels(request.getLabels())
                .status("active")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        MonitorApp saved = monitorAppRepository.save(app);
        log.info("[spring-watch: 注册应用 - app={}, endpoint={}]",
                saved.getAppName(), saved.getEndpoint());
        return saved;
    }

    public List<MonitorApp> listAll() {
        List<MonitorApp> apps = monitorAppRepository.findAll();
        log.debug("[spring-watch: 查询全部应用 - count={}]", apps.size());
        return apps;
    }

    public List<MonitorApp> listActive() {
        List<MonitorApp> apps = monitorAppRepository.findByStatus("active");
        log.debug("[spring-watch: 查询active应用 - count={}]", apps.size());
        return apps;
    }

    public MonitorApp getById(Long id) {
        MonitorApp app = monitorAppRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: " + id));
        log.debug("[spring-watch: 查询应用详情 - id={}, app={}]", id, app.getAppName());
        return app;
    }

    public MonitorApp getByAppName(String appName) {
        MonitorApp app = monitorAppRepository.findByAppName(appName)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: " + appName));
        log.debug("[spring-watch: 查询应用详情 - appName={}]", appName);
        return app;
    }

    @Transactional
    public MonitorApp update(Long id, AppRegisterRequest request) {
        MonitorApp app = getById(id);
        if (request.getEndpoint() != null) {
            app.setEndpoint(request.getEndpoint());
        }
        if (request.getScrapeInterval() != null) {
            app.setScrapeInterval(request.getScrapeInterval());
        }
        if (request.getLabels() != null) {
            app.setLabels(request.getLabels());
        }
        app.setUpdatedAt(Instant.now());
        log.info("[spring-watch: 更新应用 - app={}]", app.getAppName());
        return monitorAppRepository.save(app);
    }

    @Transactional
    public void delete(Long id) {
        MonitorApp app = getById(id);
        log.info("[spring-watch: 删除应用 - app={}]", app.getAppName());
        monitorAppRepository.delete(app);
    }

    public Map<String, Object> generateOtelConfig(Long id) {
        MonitorApp app = getById(id);

        Map<String, String> envVars = otelConfigGenerator.generateOtelConfig(app.getAppName());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appName", app.getAppName());
        result.put("endpoint", app.getEndpoint());
        result.put("environmentVariables", envVars);
        result.put("javaAgentCommand", otelConfigGenerator.generateOtelAgentCommand(
                "opentelemetry-javaagent.jar", app.getAppName()));
        log.info("[spring-watch: 生成OTel Agent配置完成 - app={}, envVars={}]",
                app.getAppName(), envVars.size());
        return result;
    }
}