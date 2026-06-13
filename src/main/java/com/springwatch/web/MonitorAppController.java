package com.springwatch.web;

import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.dto.AppRegisterRequest;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.service.MonitorAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/apps")
@RequiredArgsConstructor
public class MonitorAppController {

    private final MonitorAppService monitorAppService;

    @PostMapping
    public ApiResponse<MonitorApp> register(@Valid @RequestBody AppRegisterRequest request) {
        log.info("[spring-watch: API注册应用 - appName={}]", request.getAppName());
        return ApiResponse.ok(monitorAppService.register(request));
    }

    @GetMapping
    public ApiResponse<List<MonitorApp>> list() {
        List<MonitorApp> apps = monitorAppService.listAll();
        log.info("[spring-watch: API查询应用列表 - count={}]", apps.size());
        return ApiResponse.ok(apps);
    }

    @GetMapping("/active")
    public ApiResponse<List<MonitorApp>> listActive() {
        List<MonitorApp> apps = monitorAppService.listActive();
        log.info("[spring-watch: API查询active应用 - count={}]", apps.size());
        return ApiResponse.ok(apps);
    }

    @GetMapping("/{id}")
    public ApiResponse<MonitorApp> getById(@PathVariable Long id) {
        log.info("[spring-watch: API查询应用详情 - id={}]", id);
        return ApiResponse.ok(monitorAppService.getById(id));
    }

    @GetMapping("/by-appid/{appid}")
    public ApiResponse<MonitorApp> getByAppid(@PathVariable Long appid) {
        log.info("[spring-watch: API查询应用详情 - appid={}]", appid);
        return ApiResponse.ok(monitorAppService.getByAppid(appid));
    }

    @PutMapping("/{id}")
    public ApiResponse<MonitorApp> update(@PathVariable Long id, @RequestBody AppRegisterRequest request) {
        log.info("[spring-watch: API更新应用 - id={}, appName={}]", id, request.getAppName());
        return ApiResponse.ok(monitorAppService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        log.info("[spring-watch: API删除应用 - id={}]", id);
        monitorAppService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<MonitorApp> pause(@PathVariable Long id) {
        log.info("[spring-watch: API暂停应用 - id={}]", id);
        return ApiResponse.ok(monitorAppService.pause(id));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<MonitorApp> resume(@PathVariable Long id) {
        log.info("[spring-watch: API恢复应用 - id={}]", id);
        return ApiResponse.ok(monitorAppService.resume(id));
    }

    @GetMapping("/{id}/otel-config")
    public ApiResponse<Map<String, Object>> generateOtelConfig(@PathVariable Long id) {
        log.info("[spring-watch: API生成OTel配置 - id={}]", id);
        return ApiResponse.ok(monitorAppService.generateOtelConfig(id));
    }
}