package com.springwatch.web;

import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.dto.AppRegisterRequest;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.service.MonitorAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/apps")
@RequiredArgsConstructor
public class MonitorAppController {

    private static final int MAX_PAGE_SIZE = 200;

    private final MonitorAppService monitorAppService;

    @GetMapping
    public ApiResponse<Page<MonitorApp>> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, clampSize(size), Sort.by("id").descending());
        return ApiResponse.ok(monitorAppService.listAll(pageable));
    }

    @GetMapping("/active")
    public ApiResponse<Page<MonitorApp>> listActive(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, clampSize(size), Sort.by("id").descending());
        return ApiResponse.ok(monitorAppService.listActive(pageable));
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
        log.info("[kxj: 创建应用 - name={}, endpoint={}]", req.getAppName(), req.getEndpoint());
        return ApiResponse.ok(monitorAppService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<MonitorApp> update(@PathVariable Long id, @RequestBody AppRegisterRequest req) {
        log.info("[kxj: 更新应用 - id={}, name={}]", id, req.getAppName());
        return ApiResponse.ok(monitorAppService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        log.info("[kxj: 删除应用 - id={}]", id);
        monitorAppService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<MonitorApp> pause(@PathVariable Long id) {
        log.info("[kxj: 暂停应用 - id={}]", id);
        return ApiResponse.ok(monitorAppService.pause(id));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<MonitorApp> resume(@PathVariable Long id) {
        log.info("[kxj: 恢复应用 - id={}]", id);
        return ApiResponse.ok(monitorAppService.resume(id));
    }

    @GetMapping("/{id}/otel-config")
    public ApiResponse<Map<String, Object>> otelConfig(@PathVariable Long id) {
        return ApiResponse.ok(monitorAppService.generateOtelConfig(id));
    }

    private static int clampSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
