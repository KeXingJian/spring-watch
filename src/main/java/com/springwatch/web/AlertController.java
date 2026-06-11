package com.springwatch.web;

import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.service.AlertRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertRuleService alertRuleService;

    @PostMapping("/rules")
    public ApiResponse<AlertRule> createRule(@RequestBody Map<String, Object> body) {
        String appName = (String) body.get("appName");
        String ruleName = (String) body.get("ruleName");
        String ruleType = (String) body.getOrDefault("ruleType", "metric");
        String expression = (String) body.get("expression");
        Double thresholdValue = body.get("thresholdValue") != null
                ? ((Number) body.get("thresholdValue")).doubleValue() : null;
        Integer durationSeconds = body.get("durationSeconds") != null
                ? ((Number) body.get("durationSeconds")).intValue() : 60;
        String notifyChannels = (String) body.get("notifyChannels");

        return ApiResponse.ok(alertRuleService.createRule(
                appName, ruleName, ruleType, expression, thresholdValue, durationSeconds, notifyChannels));
    }

    @GetMapping("/rules")
    public ApiResponse<List<AlertRule>> listRules(
            @RequestParam(required = false) String app) {
        return ApiResponse.ok(alertRuleService.listRules(app));
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<AlertRule> updateRule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String expression = (String) body.get("expression");
        Double thresholdValue = body.get("thresholdValue") != null
                ? ((Number) body.get("thresholdValue")).doubleValue() : null;
        String notifyChannels = (String) body.get("notifyChannels");
        String status = (String) body.get("status");
        return ApiResponse.ok(alertRuleService.updateRule(id, expression, thresholdValue, notifyChannels, status));
    }

    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        alertRuleService.deleteRule(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/history")
    public ApiResponse<List<AlertHistory>> listHistory(
            @RequestParam(required = false) String app) {
        return ApiResponse.ok(alertRuleService.listAlertHistory(app));
    }
}