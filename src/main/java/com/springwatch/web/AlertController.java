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

        log.info("[spring-watch: API创建告警规则 - app={}, rule={}, type={}]", appName, ruleName, ruleType);
        return ApiResponse.ok(alertRuleService.createRule(
                appName, ruleName, ruleType, expression, thresholdValue, durationSeconds, notifyChannels));
    }

    @GetMapping("/rules")
    public ApiResponse<List<AlertRule>> listRules(
            @RequestParam(required = false) String app) {
        List<AlertRule> rules = alertRuleService.listRules(app);
        log.info("[spring-watch: API查询告警规则 - app={}, count={}]", app, rules.size());
        return ApiResponse.ok(rules);
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<AlertRule> updateRule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String expression = (String) body.get("expression");
        Double thresholdValue = body.get("thresholdValue") != null
                ? ((Number) body.get("thresholdValue")).doubleValue() : null;
        String notifyChannels = (String) body.get("notifyChannels");
        String status = (String) body.get("status");
        log.info("[spring-watch: API更新告警规则 - id={}, status={}]", id, status);
        return ApiResponse.ok(alertRuleService.updateRule(id, expression, thresholdValue, notifyChannels, status));
    }

    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        log.info("[spring-watch: API删除告警规则 - id={}]", id);
        alertRuleService.deleteRule(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/history")
    public ApiResponse<List<AlertHistory>> listHistory(
            @RequestParam(required = false) String app) {
        List<AlertHistory> history = alertRuleService.listAlertHistory(app);
        log.info("[spring-watch: API查询告警历史 - app={}, count={}]", app, history.size());
        return ApiResponse.ok(history);
    }
}