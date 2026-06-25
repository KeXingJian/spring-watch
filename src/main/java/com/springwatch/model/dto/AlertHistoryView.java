package com.springwatch.model.dto;

import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.entity.MonitorApp;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 告警历史展示 DTO - P0-4
 * 避免直接返回 AlertHistory 实体，触发 @ManyToOne LAZY 加载与 JSON 序列化大字段。
 */
@Getter
@Builder
public class AlertHistoryView {

    private Long id;
    private Long appid;
    private String appName;
    private Long ruleId;
    private String ruleName;
    private String alertLevel;
    private String alertMessage;
    private String notifyResult;
    private Instant resolvedAt;
    private Instant createdAt;

    public static AlertHistoryView from(AlertHistory h) {
        if (h == null) return null;
        MonitorApp app = h.getApp();
        AlertRule rule = h.getRule();
        return AlertHistoryView.builder()
                .id(h.getId())
                .appid(app == null ? null : app.getAppid())
                .appName(app == null ? null : app.getAppName())
                .ruleId(rule == null ? null : rule.getId())
                .ruleName(rule == null ? null : rule.getRuleName())
                .alertLevel(h.getAlertLevel())
                .alertMessage(h.getAlertMessage())
                .notifyResult(h.getNotifyResult())
                .resolvedAt(h.getResolvedAt())
                .createdAt(h.getCreatedAt())
                .build();
    }
}
