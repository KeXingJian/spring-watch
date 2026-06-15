package com.springwatch.alerter;

import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.AlertNotificationConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
class AlertNotifierTest {

    private JavaMailSender mailSender;
    private ObjectMapper objectMapper;
    private AlertNotificationConfigRepository notifyConfigRepository;
    private AlertNotifier notifier;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        objectMapper = new ObjectMapper();
        notifyConfigRepository = mock(AlertNotificationConfigRepository.class);
        when(notifyConfigRepository.findByAppidAndStatus(any(Long.class), eq("enabled")))
                .thenReturn(Collections.emptyList());
        notifier = new AlertNotifier(mailSender, objectMapper, notifyConfigRepository);
    }

    @Test
    void subject_usesLevelPrefix_critical() {
        AlertRule rule = ruleWith("critical", null);
        String result = notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        assertTrue(sent.getSubject().startsWith("[CRITICAL]"), "subject should start with [CRITICAL], got: " + sent.getSubject());
    }

    @Test
    void subject_usesLevelPrefix_warning() {
        AlertRule rule = ruleWith("warning", null);
        notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        assertTrue(sent.getSubject().startsWith("[WARNING]"), "subject should start with [WARNING], got: " + sent.getSubject());
    }

    @Test
    void subject_usesLevelPrefix_info() {
        AlertRule rule = ruleWith("info", null);
        notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        assertTrue(sent.getSubject().startsWith("[INFO]"), "subject should start with [INFO], got: " + sent.getSubject());
    }

    @Test
    void subject_nullLevel_defaultsToWarning() {
        AlertRule rule = ruleWith(null, null);
        notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        assertTrue(sent.getSubject().startsWith("[WARNING]"), "null level should default to [WARNING], got: " + sent.getSubject());
    }

    @Test
    void resolved_usesResolvedPrefix_regardlessOfLevel() {
        AlertRule rule = ruleWith("critical", null);
        notifier.notify(rule, event(), "resolved");
        SimpleMailMessage sent = captureSent();
        assertTrue(sent.getSubject().startsWith("[RESOLVED]"), "resolved should use [RESOLVED], got: " + sent.getSubject());
    }

    @Test
    void template_rendersAllPlaceholders() {
        AlertRule rule = ruleWith("critical",
                "[{{level}}][{{type}}] {{app}} {{metric}}={{value}} 阈值={{threshold}} 规则={{rule}}");
        notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        String body = sent.getText();
        log.info("[TEST] body={}", body);
        assertTrue(body.contains("[CRITICAL]"), "level placeholder");
        assertTrue(body.contains("[FIRING]"), "type placeholder");
        assertTrue(body.contains("myapp"), "app placeholder");
        assertTrue(body.contains("jvm_heap"), "metric placeholder");
        assertTrue(body.contains("85.00"), "value placeholder");
        assertTrue(body.contains("80.00"), "threshold placeholder");
        assertTrue(body.contains("test-rule"), "rule placeholder");
    }

    @Test
    void template_rendersTimePlaceholder() {
        AlertRule rule = ruleWith("warning", "time={{time}}");
        notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        assertTrue(sent.getText().matches("(?s).*time=\\d{4}-\\d{2}-\\d{2}.*"), "time should be ISO format, got: " + sent.getText());
    }

    @Test
    void template_rendersExpressionAndAppid() {
        AlertRule rule = AlertRule.builder()
                .ruleName("r1")
                .app(app(1L, "myapp"))
                .expression("value > 80")
                .thresholdValue(80.0)
                .level("warning")
                .template("{{expression}} on {{appid}}")
                .notifyChannels("{\"email\":\"ops@example.com\"}")
                .build();
        notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        assertEquals("value > 80 on 100", sent.getText().trim());
    }

    @Test
    void noTemplate_usesDefaultBody() {
        AlertRule rule = ruleWith("warning", null);
        notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        assertTrue(sent.getText().contains("告警触发"), "default body should contain '告警触发'");
    }

    @Test
    void noChannels_skipped() {
        AlertRule rule = AlertRule.builder()
                .ruleName("r1")
                .app(app(1L, "myapp"))
                .expression("x > 0")
                .level("warning")
                .notifyChannels(null)
                .build();
        String result = notifier.notify(rule, event(), "firing");
        assertTrue(result.contains("no_email"), "no channels + empty config should return no_email, got: " + result);
    }

    @Test
    void emptyChannels_skipped() {
        AlertRule rule = AlertRule.builder()
                .ruleName("r1")
                .app(app(1L, "myapp"))
                .expression("x > 0")
                .level("warning")
                .notifyChannels("{}")
                .build();
        String result = notifier.notify(rule, event(), "firing");
        assertTrue(result.contains("no_email"), "empty channels + empty config should fall back to no_email, got: " + result);
    }

    @Test
    void noEmailKey_skipped() {
        AlertRule rule = AlertRule.builder()
                .ruleName("r1")
                .app(app(1L, "myapp"))
                .expression("x > 0")
                .level("warning")
                .notifyChannels("{\"webhook\":\"http://x.com\"}")
                .build();
        String result = notifier.notify(rule, event(), "firing");
        assertTrue(result.contains("no_email"), "no email key should return skipped, got: " + result);
    }

    @Test
    void invalidJson_failedResult() {
        AlertRule rule = AlertRule.builder()
                .ruleName("r1")
                .app(app(1L, "myapp"))
                .expression("x > 0")
                .level("warning")
                .notifyChannels("not a json")
                .build();
        String result = notifier.notify(rule, event(), "firing");
        assertTrue(result.contains("invalid_channels"), "invalid json should return failed, got: " + result);
    }

    @Test
    void multipleRecipients_commaSeparated() {
        AlertRule rule = ruleWith("critical", "alert!");
        rule.setNotifyChannels("{\"email\":\"a@x.com,b@x.com\"}");
        String result = notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        String[] to = sent.getTo();
        assertNotNull(to);
        assertEquals("a@x.com,b@x.com", String.join(",", to));
        assertNotNull(result);
    }

    @Test
    void fromAddress_isSet() {
        org.springframework.test.util.ReflectionTestUtils.setField(notifier, "from", "alert@x.com");
        AlertRule rule = ruleWith("warning", "test");
        notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        assertNotNull(sent.getFrom(), "from should be set");
        assertEquals("alert@x.com", sent.getFrom());
    }

    @Test
    void mailSendFailure_returnedInResult() {
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        AlertRule rule = ruleWith("warning", "test");
        String result = notifier.notify(rule, event(), "firing");
        assertTrue(result.contains("failed"), "smtp failure should return failed, got: " + result);
        assertTrue(result.contains("smtp down"), "should include error message, got: " + result);
    }

    @Test
    void noRuleChannels_fallbackToConfigTable_sends() {
        AlertRule rule = AlertRule.builder()
                .id(1L)
                .ruleName("test-rule")
                .app(app(100L, "myapp"))
                .expression("x > 0")
                .level("warning")
                .notifyChannels(null)
                .build();
        com.springwatch.model.entity.AlertNotificationConfig cfg =
                com.springwatch.model.entity.AlertNotificationConfig.builder()
                        .id(10L).appid(100L).target("ops@example.com").status("enabled").build();
        when(notifyConfigRepository.findByAppidAndStatus(100L, "enabled"))
                .thenReturn(java.util.List.of(cfg));
        notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        String[] to = sent.getTo();
        assertNotNull(to);
        assertEquals("ops@example.com", String.join(",", to));
    }

    @Test
    void noRuleChannels_noConfigMatch_skipped() {
        AlertRule rule = AlertRule.builder()
                .id(1L)
                .ruleName("test-rule")
                .app(app(100L, "myapp"))
                .expression("x > 0")
                .level("warning")
                .notifyChannels(null)
                .build();
        when(notifyConfigRepository.findByAppidAndStatus(100L, "enabled"))
                .thenReturn(Collections.emptyList());
        String result = notifier.notify(rule, event(), "firing");
        assertTrue(result.contains("no_email"), "no config match should return no_email skipped, got: " + result);
    }

    @Test
    void ruleChannelHasEmail_configTableIgnored() {
        AlertRule rule = ruleWith("warning", "test");
        notifier.notify(rule, event(), "firing");
        SimpleMailMessage sent = captureSent();
        String[] to = sent.getTo();
        assertNotNull(to);
        assertEquals("ops@example.com", String.join(",", to));
    }

    private AlertRule ruleWith(String level, String template) {
        return AlertRule.builder()
                .id(1L)
                .ruleName("test-rule")
                .app(app(100L, "myapp"))
                .expression("x > 0")
                .thresholdValue(80.0)
                .level(level)
                .template(template)
                .notifyChannels("{\"email\":\"ops@example.com\"}")
                .build();
    }

    private MonitorApp app(Long appid, String name) {
        return MonitorApp.builder().appid(appid).appName(name).build();
    }

    private MetricEvent event() {
        return MetricEvent.builder()
                .appid(100L)
                .metricName("jvm_heap")
                .method("GET")
                .value(85.0)
                .timestamp(Instant.now())
                .build();
    }

    private SimpleMailMessage captureSent() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }
}
