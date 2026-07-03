package com.springwatch.web;

import com.springwatch.alerter.AlertNotifier;
import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.dto.NotificationConfigRequest;
import com.springwatch.model.entity.AlertNotificationConfig;
import com.springwatch.service.NotificationConfigService;
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
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 200;

    private final NotificationConfigService configService;

    @GetMapping("/configs")
    public ApiResponse<Page<AlertNotificationConfig>> listConfigs(
            @RequestParam(value = "appid", required = false) Long appid,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, clampSize(size), Sort.by("id").descending());
        Page<AlertNotificationConfig> list = (appid == null)
                ? configService.listAll(pageable)
                : configService.listByAppid(appid, pageable);
        return ApiResponse.ok(list);
    }

    @PostMapping("/configs")
    public ApiResponse<AlertNotificationConfig> createConfig(@RequestBody @Valid NotificationConfigRequest req) {
        log.info("[kxj: 创建通知配置 - appid={}, target={}]", req.getAppid(), req.getTarget());
        return ApiResponse.ok(configService.create(req));
    }

    @PutMapping("/configs/{id}")
    public ApiResponse<AlertNotificationConfig> updateConfig(@PathVariable Long id, @RequestBody NotificationConfigRequest req) {
        log.info("[kxj: 更新通知配置 - id={}]", id);
        return ApiResponse.ok(configService.update(id, req));
    }

    @DeleteMapping("/configs/{id}")
    public ApiResponse<Void> deleteConfig(@PathVariable Long id) {
        log.info("[kxj: 删除通知配置 - id={}]", id);
        configService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> testEmail(@RequestParam(value = "to", required = false) String to) {
        if (to == null || to.isBlank()) {
            return ApiResponse.fail(40001, "缺少必填参数 to");
        }
        log.info("[kxj: 测试邮件 - to={}]", to);
        String raw = configService.testEmail(to);
        boolean ok = raw != null && raw.contains("\"status\":\"ok\"");
        return ApiResponse.ok(Map.of(
                "ok", ok,
                "raw", raw == null ? "" : raw
        ));
    }

    private static int clampSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
