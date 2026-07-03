package com.springwatch.web;

import com.springwatch.model.dto.AlertHistoryView;
import com.springwatch.model.dto.AlertRuleRequest;
import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.repository.AlertHistoryRepository;
import com.springwatch.service.AlertRuleService;
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

@Slf4j
@RestController
@RequestMapping("/api/alert")
@RequiredArgsConstructor
public class AlertController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AlertRuleService alertRuleService;
    private final AlertHistoryRepository alertHistoryRepository;

    @GetMapping("/rules")
    public ApiResponse<Page<AlertRule>> listRules(
            @RequestParam(value = "appid", required = false) Long appid,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, clampSize(size), Sort.by("id").descending());
        Page<AlertRule> rules = (appid == null)
                ? alertRuleService.listAll(pageable)
                : alertRuleService.listByAppid(appid, pageable);
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
        log.info("[kxj: 创建告警规则 - name={}, type={}, appid={}]", req.getRuleName(), req.getRuleType(), req.getAppid());
        return ApiResponse.ok(alertRuleService.create(req));
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<AlertRule> updateRule(@PathVariable Long id, @RequestBody AlertRuleRequest req) {
        log.info("[kxj: 更新告警规则 - id={}]", id);
        return ApiResponse.ok(alertRuleService.update(id, req));
    }

    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        log.info("[kxj: 删除告警规则 - id={}]", id);
        alertRuleService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/rules/{id}/toggle")
    public ApiResponse<AlertRule> toggleRule(@PathVariable Long id) {
        log.info("[kxj: 切换告警规则状态 - id={}]", id);
        return ApiResponse.ok(alertRuleService.toggle(id));
    }

    @GetMapping("/history")
    public ApiResponse<Page<AlertHistoryView>> listHistory(
            @RequestParam(value = "appid", required = false) Long appid,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, clampSize(size), Sort.by("createdAt").descending());
        Page<AlertHistory> history = (appid == null)
                ? alertHistoryRepository.findAll(pageable)
                : alertHistoryRepository.findByAppAppid(appid, pageable);
        Page<AlertHistoryView> views = history.map(AlertHistoryView::from);
        return ApiResponse.ok(views);
    }

    private static int clampSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
