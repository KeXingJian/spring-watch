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
        return ApiResponse.ok(monitorAppService.listAll());
    }

    @GetMapping("/active")
    public ApiResponse<List<MonitorApp>> listActive() {
        return ApiResponse.ok(monitorAppService.listActive());
    }

    @GetMapping("/{id}")
    public ApiResponse<MonitorApp> getById(@PathVariable Long id) {
        return ApiResponse.ok(monitorAppService.getById(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<MonitorApp> update(@PathVariable Long id, @RequestBody AppRegisterRequest request) {
        return ApiResponse.ok(monitorAppService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        monitorAppService.delete(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/otel-config")
    public ApiResponse<Map<String, Object>> generateOtelConfig(@PathVariable Long id) {
        return ApiResponse.ok(monitorAppService.generateOtelConfig(id));
    }
}