package com.springwatch.web;

import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.dto.AppRegisterRequest;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.service.MonitorAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/apps")
@RequiredArgsConstructor
public class MonitorAppController {

    private final MonitorAppService monitorAppService;

    @GetMapping
    public ApiResponse<List<MonitorApp>> list() {
        log.debug("[spring-watch: 列出全部应用]");
        return ApiResponse.ok(monitorAppService.listAll());
    }

    @GetMapping("/active")
    public ApiResponse<List<MonitorApp>> listActive() {
        log.debug("[spring-watch: 列出active应用]");
        return ApiResponse.ok(monitorAppService.listActive());
    }

    @GetMapping("/{id}")
    public ApiResponse<MonitorApp> getById(@PathVariable Long id) {
        return monitorAppService.findById(id)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail(404, "应用不存在: " + id));
    }

    @GetMapping("/by-appid/{appid}")
    public ApiResponse<MonitorApp> getByAppid(@PathVariable Long appid) {
        return monitorAppService.findByAppid(appid)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail(404, "应用不存在: appid=" + appid));
    }

    @PostMapping
    public ApiResponse<MonitorApp> create(@RequestBody @Valid AppRegisterRequest req) {
        log.info("[spring-watch: 创建应用 - name={}, endpoint={}]", req.getAppName(), req.getEndpoint());
        return ApiResponse.ok(monitorAppService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<MonitorApp> update(@PathVariable Long id, @RequestBody AppRegisterRequest req) {
        log.info("[spring-watch: 更新应用 - id={}, name={}]", id, req.getAppName());
        return ApiResponse.ok(monitorAppService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        log.info("[spring-watch: 删除应用 - id={}]", id);
        monitorAppService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<MonitorApp> pause(@PathVariable Long id) {
        log.info("[spring-watch: 暂停应用 - id={}]", id);
        return ApiResponse.ok(monitorAppService.pause(id));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<MonitorApp> resume(@PathVariable Long id) {
        log.info("[spring-watch: 恢复应用 - id={}]", id);
        return ApiResponse.ok(monitorAppService.resume(id));
    }

    @GetMapping("/{id}/otel-config")
    public ApiResponse<Map<String, Object>> otelConfig(@PathVariable Long id) {
        return ApiResponse.ok(monitorAppService.generateOtelConfig(id));
    }
}
