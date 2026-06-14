package com.springwatch.alerter;

import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.MetricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertNotifier {

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;

    @Value("${spring-watch.alert.mail.from:alert@example.com}")
    private String from;

    @Value("${spring-watch.alert.mail.from-name:spring-watch}")
    private String fromName;

    public String notify(AlertRule rule, MetricEvent event, String type) {
        if (rule.getNotifyChannels() == null || rule.getNotifyChannels().isBlank()) {
            log.debug("[Alerter] 无通知渠道 - ruleId={}", rule.getId());
            return "{\"status\":\"skipped\",\"reason\":\"no_channels\"}";
        }
        Map<String, String> channels;
        try {
            channels = objectMapper.readValue(rule.getNotifyChannels(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("[Alerter] 通知渠道配置解析失败 - ruleId={}, raw={}, error={}",
                    rule.getId(), rule.getNotifyChannels(), e.getMessage());
            return "{\"status\":\"failed\",\"reason\":\"invalid_channels\"}";
        }
        if (channels.isEmpty()) {
            return "{\"status\":\"skipped\",\"reason\":\"empty_channels\"}";
        }
        String email = channels.get("email");
        if (email == null || email.isBlank()) {
            log.debug("[Alerter] 未配置email渠道 - ruleId={}", rule.getId());
            return "{\"status\":\"skipped\",\"reason\":\"no_email\"}";
        }
        return sendEmail(email, rule, event, type);
    }

    private String sendEmail(String to, AlertRule rule, MetricEvent event, String type) {
        String subject = buildSubject(rule, event, type);
        String body = buildBody(rule, event, type);
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            log.info("[Alerter] 邮件发送成功 - to={}, type={}, ruleId={}, appid={}",
                    to, type, rule.getId(), event.getAppid());
            return "{\"status\":\"ok\",\"channel\":\"email\",\"to\":\"" + to + "\"}";
        } catch (Exception e) {
            log.warn("[Alerter] 邮件发送失败 - to={}, type={}, error={}", to, type, e.getMessage());
            return "{\"status\":\"failed\",\"channel\":\"email\",\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String buildSubject(AlertRule rule, MetricEvent event, String type) {
        String appName = rule.getApp() != null ? rule.getApp().getAppName() : "appid=" + event.getAppid();
        String level = resolveLevel(rule);
        String prefix = "firing".equals(type) ? "[" + level + "]" : "[RESOLVED]";
        return String.format("%s %s - %s = %.2f (rule: %s)",
                prefix, appName, event.getMetricName(),
                event.getValue() != null ? event.getValue() : 0.0,
                rule.getRuleName() != null ? rule.getRuleName() : "rule-" + rule.getId());
    }

    private String buildBody(AlertRule rule, MetricEvent event, String type) {
        if (rule.getTemplate() != null && !rule.getTemplate().isBlank()) {
            return renderTemplate(rule.getTemplate(), rule, event, type);
        }
        return defaultBody(rule, event, type);
    }

    private String defaultBody(AlertRule rule, MetricEvent event, String type) {
        String appName = rule.getApp() != null ? rule.getApp().getAppName() : "appid=" + event.getAppid();
        String appid = String.valueOf(event.getAppid());
        String metric = event.getMetricName() != null ? event.getMetricName() : "unknown";
        String value = event.getValue() != null ? String.format("%.2f", event.getValue()) : "null";
        String expression = rule.getExpression() != null ? rule.getExpression() : "";
        String ruleName = rule.getRuleName() != null ? rule.getRuleName() : "rule-" + rule.getId();
        String level = resolveLevel(rule);
        String time = Instant.now().toString();

        if ("firing".equals(type)) {
            return String.format("""
                    [%s] 告警触发
                    应用: %s (appid=%s)
                    指标: %s = %s
                    规则: %s (表达式: %s)
                    时间: %s
                    """, level, appName, appid, metric, value, ruleName, expression, time);
        } else {
            return String.format("""
                    [RESOLVED][%s] 告警恢复
                    应用: %s (appid=%s)
                    指标: %s = %s
                    规则: %s (表达式: %s)
                    恢复时间: %s
                    """, level, appName, appid, metric, value, ruleName, expression, time);
        }
    }

    private String renderTemplate(String template, AlertRule rule, MetricEvent event, String type) {
        String level = resolveLevel(rule);
        String appName = rule.getApp() != null ? rule.getApp().getAppName() : "appid=" + event.getAppid();
        String value = event.getValue() != null ? String.format("%.2f", event.getValue()) : "null";
        String threshold = rule.getThresholdValue() != null
                ? String.format("%.2f", rule.getThresholdValue()) : "null";
        String time = Instant.now().toString();

        return template
                .replace("{{level}}", level)
                .replace("{{type}}", type.toUpperCase())
                .replace("{{app}}", appName)
                .replace("{{appid}}", String.valueOf(event.getAppid()))
                .replace("{{metric}}", event.getMetricName() != null ? event.getMetricName() : "")
                .replace("{{value}}", value)
                .replace("{{threshold}}", threshold)
                .replace("{{rule}}", rule.getRuleName() != null ? rule.getRuleName() : "")
                .replace("{{time}}", time)
                .replace("{{expression}}", rule.getExpression() != null ? rule.getExpression() : "");
    }

    private String resolveLevel(AlertRule rule) {
        String level = rule.getLevel();
        if (level == null || level.isBlank()) {
            return "WARNING";
        }
        return level.toUpperCase();
    }
}
