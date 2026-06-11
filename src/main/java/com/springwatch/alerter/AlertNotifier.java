package com.springwatch.alerter;

import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertNotifier {

    private final AlertHistoryRepository alertHistoryRepository;

    public void notify(AlertRule rule, MetricEvent event) {
        String alertLevel = determineLevel(rule, event);
        String message = buildMessage(rule, event, alertLevel);

        log.info("[spring-watch: 发送告警 - rule={}, app={}, level={}, message={}]",
                rule.getRuleName(), event.getAppName(), alertLevel, message);

        AlertHistory history = AlertHistory.builder()
                .rule(rule)
                .app(rule.getApp())
                .alertLevel(alertLevel)
                .alertMessage(message)
                .build();
        AlertHistory saved = alertHistoryRepository.save(history);
        log.info("[spring-watch: 告警历史持久化完成 - historyId={}, rule={}, app={}, level={}]",
                saved.getId(), rule.getRuleName(), event.getAppName(), alertLevel);

        if (rule.getNotifyChannels() != null && !rule.getNotifyChannels().isBlank()) {
            sendWebhook(rule, message, event);
        } else {
            log.debug("[spring-watch: 告警无通知渠道 - rule={}, 仅持久化历史]", rule.getRuleName());
        }
    }

    private String determineLevel(AlertRule rule, MetricEvent event) {
        if (rule.getThresholdValue() != null && event.getValue() != null) {
            double exceedRatio = event.getValue() / rule.getThresholdValue();
            if (exceedRatio >= 2.0) {
                return "critical";
            }
        }
        return "warning";
    }

    private String buildMessage(AlertRule rule, MetricEvent event, String level) {
        return String.format("[%s] 应用 %s 指标 %s 触发告警: 当前值=%.2f, 规则=%s, 时间=%s",
                level.toUpperCase(), event.getAppName(), event.getMetricName(),
                event.getValue(), rule.getExpression(), Instant.now());
    }

    private void sendWebhook(AlertRule rule, String message, MetricEvent event) {
        log.info("[spring-watch: Webhook通知 - rule={}, channels={}, app={}]",
                rule.getRuleName(), rule.getNotifyChannels(), event.getAppName());
    }
}