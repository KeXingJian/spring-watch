package com.springwatch.web;

import com.springwatch.model.dto.AlertRuleRequest;
import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.repository.AlertHistoryRepository;
import com.springwatch.service.AlertRuleService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/alert")
@RequiredArgsConstructor
public class AlertController {

    private final AlertRuleService alertRuleService;
    private final AlertHistoryRepository alertHistoryRepository;

    @GetMapping("/rules")
    public ApiResponse<List<AlertRule>> listRules(@RequestParam(value = "appid", required = false) Long appid) {
        List<AlertRule> rules = (appid == null) ? alertRuleService.listAll() : alertRuleService.listByAppid(appid);
        return ApiResponse.ok(rules);
    }

    @GetMapping("/rules/{id}")
    public ApiResponse<AlertRule> getRule(@PathVariable Long id) {
        return alertRuleService.findById(id)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail(404, "规则不存在: " + id));
    }

    @PostMapping("/rules")
    public ApiResponse<AlertRule> createRule(@RequestBody @Valid AlertRuleRequest req) {
        log.info("[spring-watch: 创建告警规则 - name={}, type={}, appid={}]", req.getRuleName(), req.getRuleType(), req.getAppid());
        return ApiResponse.ok(alertRuleService.create(req));
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<AlertRule> updateRule(@PathVariable Long id, @RequestBody AlertRuleRequest req) {
        log.info("[spring-watch: 更新告警规则 - id={}]", id);
        return ApiResponse.ok(alertRuleService.update(id, req));
    }

    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        log.info("[spring-watch: 删除告警规则 - id={}]", id);
        alertRuleService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/rules/{id}/toggle")
    public ApiResponse<AlertRule> toggleRule(@PathVariable Long id) {
        log.info("[spring-watch: 切换告警规则状态 - id={}]", id);
        return ApiResponse.ok(alertRuleService.toggle(id));
    }

    @GetMapping("/history")
    public ApiResponse<List<AlertHistory>> listHistory(
            @RequestParam(value = "appid", required = false) Long appid,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        List<AlertHistory> all = alertHistoryRepository.findAll();
        List<AlertHistory> filtered = (appid == null) ? all : all.stream()
                .filter(h -> h.getApp() != null && appid.equals(h.getApp().getAppid()))
                .toList();
        int size = Math.min(filtered.size(), limit <= 0 ? 200 : limit);
        return ApiResponse.ok(filtered.subList(0, size));
    }
}
