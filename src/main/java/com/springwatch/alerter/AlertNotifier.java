package com.springwatch.alerter;

import com.springwatch.model.entity.AlertNotificationConfig;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.AlertNotificationConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertNotifier {

    @Value("${spring-watch.alert.enabled:true}")
    private boolean alertEnabled;

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final AlertNotificationConfigRepository notifyConfigRepository;
    private final AsyncMailExecutor mailExecutor;

    @Value("${spring-watch.alert.mail.from:alert@example.com}")
    private String from;

    @Value("${spring-watch.alert.mail.from-name:spring-watch}")
    private String fromName;

    private static final String INVALID_CHANNELS_MARKER = "__INVALID_CHANNELS__";

    @PostConstruct
    void init() {
        mailExecutor.start();
    }

    public String notify(AlertRule rule, MetricEvent event, String type) {
        if (!alertEnabled) {
            log.debug("[Alerter] notify 跳过 - alert.enabled=false, ruleId={}, appid={}",
                    rule != null ? rule.getId() : null, event.getAppid());
            return "{\"status\":\"skipped\",\"reason\":\"alert_disabled\"}";
        }
        log.debug("[Alerter] notify 入口 - ruleId={}, appid={}, type={}", rule.getId(), event.getAppid(), type);
        String email = resolveEmail(rule, event);
        if (INVALID_CHANNELS_MARKER.equals(email)) {
            return "{\"status\":\"failed\",\"reason\":\"invalid_channels\"}";
        }
        if (email == null || email.isBlank()) {
            log.debug("[Alerter] 未配置email渠道 - ruleId={}, appid={}", rule.getId(), event.getAppid());
            return "{\"status\":\"skipped\",\"reason\":\"no_email\"}";
        }
        // P1-10: SMTP 调用放到独立线程池，避免 SMTP 慢响应阻塞告警评估
        mailExecutor.submit(() -> sendEmail(email, rule, event, type));
        return "{\"status\":\"queued\",\"channel\":\"email\",\"to\":\"" + email + "\"}";
    }

    /**
     * kxj: 邮箱解析-优先使用规则notify_channels,否则回退到通知配置表
     * 返回 INVALID_CHANNELS_MARKER 表示规则配置JSON解析失败(不静默回退)
     */
    private String resolveEmail(AlertRule rule, MetricEvent event) {
        if (rule.getNotifyChannels() == null || rule.getNotifyChannels().isBlank()) {
            log.debug("[Alerter] 规则未配置notify_channels, 查通知配置表 - ruleId={}, appid={}",
                    rule.getId(), event.getAppid());
        } else {
            Map<String, String> channels;
            try {
                channels = objectMapper.readValue(rule.getNotifyChannels(), new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                log.warn("[Alerter] 通知渠道配置解析失败 - ruleId={}, raw={}, error={}",
                        rule.getId(), rule.getNotifyChannels(), e.getMessage());
                return INVALID_CHANNELS_MARKER;
            }
            if (channels.isEmpty()) {
                log.debug("[Alerter] 规则通知渠道为空, 查通知配置表 - ruleId={}, appid={}", rule.getId(), event.getAppid());
                return lookupConfigTargets(event.getAppid());
            }
            String email = channels.get("email");
            if (email != null && !email.isBlank()) {
                return email;
            }
            log.debug("[Alerter] 规则notify_channels未配置email, 查通知配置表 - ruleId={}, appid={}",
                    rule.getId(), event.getAppid());
        }
        return lookupConfigTargets(event.getAppid());
    }

    private String lookupConfigTargets(Long appid) {
        try {
            List<AlertNotificationConfig> configs = notifyConfigRepository.findByAppidAndStatus(appid, "enabled");
            if (configs == null || configs.isEmpty()) {
                log.debug("[Alerter] 通知配置表无启用项 - appid={}", appid);
                return null;
            }
            String joined = configs.stream()
                    .map(AlertNotificationConfig::getTarget)
                    .filter(t -> t != null && !t.isBlank())
                    .distinct()
                    .collect(Collectors.joining(","));
            log.info("[Alerter] 通知配置表命中 - appid={}, count={}, targets={}", appid, configs.size(), joined);
            return joined;
        } catch (Exception e) {
            log.warn("[Alerter] 通知配置表查询失败 - appid={}, error={}", appid, e.getMessage());
            return null;
        }
    }

    private void sendEmail(String to, AlertRule rule, MetricEvent event, String type) {
        String[] toArr = Arrays.stream(to.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (toArr.length == 0) {
            log.warn("[Alerter] 邮件收件人解析为空 - to={}", to);
            return;
        }
        String subject = buildSubject(rule, event, type);
        String body = buildBody(rule, event, type);
        log.debug("[Alerter] sendEmail - to={}, type={}, subject={}", Arrays.toString(toArr), type, subject);
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(toArr);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            log.info("[Alerter] 邮件发送成功 - to={}, type={}, ruleId={}, appid={}",
                    Arrays.toString(toArr), type, rule.getId(), event.getAppid());
        } catch (Exception e) {
            log.warn("[Alerter] 邮件发送失败 - to={}, type={}, error={}", Arrays.toString(toArr), type, e.getMessage());
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
        String logDetail = renderLogDetail(rule, event);

        if ("firing".equals(type)) {
            return String.format("""
                    [%s] 告警触发
                    应用: %s (appid=%s)
                    指标: %s = %s
                    规则: %s (表达式: %s)
                    时间: %s
                    %s""", level, appName, appid, metric, value, ruleName, expression, time, logDetail);
        } else {
            return String.format("""
                    [RESOLVED][%s] 告警恢复
                    应用: %s (appid=%s)
                    指标: %s = %s
                    规则: %s (表达式: %s)
                    恢复时间: %s
                    %s""", level, appName, appid, metric, value, ruleName, expression, time, logDetail);
        }
    }

    private String renderLogDetail(AlertRule rule, MetricEvent event) {
        String ruleType = rule.getRuleType();
        if (!"log_keyword".equals(ruleType) && !"log_new_pattern".equals(ruleType)) {
            return "";
        }
        Map<String, String> tags = event.getTags();
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("日志详情:\n");
        appendIfPresent(sb, tags, "level", "  级别");
        appendIfPresent(sb, tags, "logger", "  Logger");
        appendIfPresent(sb, tags, "method", "  方法");
        appendIfPresent(sb, tags, "host", "  主机");
        appendIfPresent(sb, tags, "traceId", "  TraceId");
        appendIfPresent(sb, tags, "fingerprint", "  指纹");
        appendIfPresent(sb, tags, "message", "  消息");
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, Map<String, String> tags, String key, String label) {
        String v = tags.get(key);
        if (v != null && !v.isEmpty()) {
            sb.append(label).append(": ").append(v).append('\n');
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

    /**
     * kxj: 测试发送邮件 - 平台配置页验证 SMTP 连通性
     */
    public String sendTestEmail(String to) {
        if (to == null || to.isBlank()) {
            return "{\"status\":\"failed\",\"reason\":\"empty_recipient\"}";
        }
        String[] toArr = Arrays.stream(to.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (toArr.length == 0) {
            return "{\"status\":\"failed\",\"reason\":\"empty_recipient\"}";
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(toArr);
            msg.setSubject("[spring-watch] 测试邮件");
            msg.setText("这是一封来自 spring-watch 的测试邮件。\n时间: " + Instant.now() + "\n如果您收到此邮件,说明 SMTP 配置正确。");
            mailSender.send(msg);
            log.info("[kxj: AlertNotifier sendTestEmail - to={}, 成功]", Arrays.toString(toArr));
            return "{\"status\":\"ok\",\"to\":\"" + to + "\"}";
        } catch (Exception e) {
            log.warn("[kxj: AlertNotifier sendTestEmail 失败 - to={}, error={}]", Arrays.toString(toArr), e.getMessage());
            return "{\"status\":\"failed\",\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
