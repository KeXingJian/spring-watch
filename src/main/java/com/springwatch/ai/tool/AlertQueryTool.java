package com.springwatch.ai.tool;

import tools.jackson.databind.ObjectMapper;
import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.repository.AlertHistoryRepository;
import com.springwatch.service.AlertRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertQueryTool {

    private final AlertRuleService alertRuleService;
    private final AlertHistoryRepository alertHistoryRepository;
    private final ObjectMapper objectMapper;

    @Tool(description = "查询告警规则列表,可按应用过滤。返回规则名/类型/表达式/阈值/级别/状态")
    @Transactional(readOnly = true)
    public String listRules(
            @ToolParam(description = "监控应用 appid,可为空表示全部") Long appid) {
        log.info("[kxj: AI工具 告警规则 - appid={}]", appid);
        List<AlertRule> rules = appid == null
                ? alertRuleService.listAll(PageRequest.of(0, 100)).getContent()
                : alertRuleService.listByAppid(appid, PageRequest.of(0, 100)).getContent();
        List<RuleRow> rows = rules.stream().map(RuleRow::from).toList();
        return toJson(Map.of("count", rows.size(), "rows", rows));
    }

    @Tool(description = "查询告警历史,默认只查未恢复(FIRING)的告警,可指定应用过滤。返回规则名/级别/消息/触发时间/恢复时间")
    @Transactional(readOnly = true)
    public String listHistory(
            @ToolParam(description = "监控应用 appid,可为空表示全部") Long appid,
            @ToolParam(description = "是否只查未恢复告警,true/false,默认 true") Boolean onlyFiring,
            @ToolParam(description = "最大条数,默认20") Integer limit) {
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 50);
        boolean firing = onlyFiring == null || onlyFiring;
        log.info("[kxj: AI工具 告警历史 - appid={}, onlyFiring={}, limit={}]", appid, firing, size);
        List<AlertHistory> histories;
        if (appid != null) {
            histories = alertHistoryRepository.findByAppAppid(appid, PageRequest.of(0, size)).getContent();
        } else if (firing) {
            histories = alertHistoryRepository.findByResolvedAtIsNullOrderByCreatedAtDesc(PageRequest.of(0, size));
        } else {
            histories = alertHistoryRepository.findTop20ByOrderByCreatedAtDesc();
        }
        List<HistoryRow> rows = histories.stream().map(HistoryRow::from).toList();
        return toJson(Map.of("count", rows.size(), "rows", rows));
    }

    public record RuleRow(Long id, String ruleName, String ruleType, String expression,
                          Double thresholdValue, String level, String status, Long appid) {
        static RuleRow from(AlertRule rule) {
            return new RuleRow(rule.getId(), rule.getRuleName(), rule.getRuleType(), rule.getExpression(),
                    rule.getThresholdValue(), rule.getLevel(), rule.getStatus(),
                    rule.getApp() == null ? null : rule.getApp().getAppid());
        }
    }

    public record HistoryRow(Long id, String ruleName, Long appid, String appName, String level,
                             String message, Instant createdAt, Instant resolvedAt) {
        static HistoryRow from(AlertHistory h) {
            return new HistoryRow(h.getId(),
                    h.getRule() == null ? null : h.getRule().getRuleName(),
                    h.getApp() == null ? null : h.getApp().getAppid(),
                    h.getApp() == null ? null : h.getApp().getAppName(),
                    h.getAlertLevel(), h.getAlertMessage(), h.getCreatedAt(), h.getResolvedAt());
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[kxj: AI工具 JSON序列化失败 - error={}]", e.getMessage());
            return "{}";
        }
    }
}