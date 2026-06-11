package com.springwatch.service;

import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.AlertHistoryRepository;
import com.springwatch.repository.AlertRuleRepository;
import com.springwatch.repository.MonitorAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private final AlertRuleRepository alertRuleRepository;
    private final AlertHistoryRepository alertHistoryRepository;
    private final MonitorAppRepository monitorAppRepository;

    @Transactional
    public AlertRule createRule(String appName, String ruleName, String ruleType,
                                 String expression, Double thresholdValue, Integer durationSeconds,
                                 String notifyChannels) {
        MonitorApp app = monitorAppRepository.findByAppName(appName)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: " + appName));

        AlertRule rule = AlertRule.builder()
                .app(app)
                .ruleName(ruleName)
                .ruleType(ruleType)
                .expression(expression)
                .thresholdValue(thresholdValue)
                .durationSeconds(durationSeconds != null ? durationSeconds : 60)
                .notifyChannels(notifyChannels)
                .status("enabled")
                .createdAt(Instant.now())
                .build();

        AlertRule saved = alertRuleRepository.save(rule);
        log.info("[spring-watch: 创建告警规则 - app={}, rule={}, type={}]",
                appName, ruleName, ruleType);
        return saved;
    }

    public List<AlertRule> listRules(String appName) {
        List<AlertRule> rules;
        if (appName != null) {
            rules = alertRuleRepository.findByAppAppNameAndStatus(appName, "enabled");
        } else {
            rules = alertRuleRepository.findByStatus("enabled");
        }
        log.debug("[spring-watch: 查询告警规则 - app={}, count={}]", appName, rules.size());
        return rules;
    }

    public List<AlertRule> listAllRules() {
        List<AlertRule> rules = alertRuleRepository.findAll();
        log.debug("[spring-watch: 查询全部告警规则 - count={}]", rules.size());
        return rules;
    }

    public AlertRule getById(Long id) {
        AlertRule rule = alertRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在: " + id));
        log.debug("[spring-watch: 查询告警规则详情 - id={}, rule={}]", id, rule.getRuleName());
        return rule;
    }

    @Transactional
    public AlertRule updateRule(Long id, String expression, Double thresholdValue,
                                 String notifyChannels, String status) {
        AlertRule rule = getById(id);
        if (expression != null) {
            rule.setExpression(expression);
        }
        if (thresholdValue != null) {
            rule.setThresholdValue(thresholdValue);
        }
        if (notifyChannels != null) {
            rule.setNotifyChannels(notifyChannels);
        }
        if (status != null) {
            rule.setStatus(status);
        }
        log.info("[spring-watch: 更新告警规则 - id={}, rule={}]", id, rule.getRuleName());
        return alertRuleRepository.save(rule);
    }

    @Transactional
    public void deleteRule(Long id) {
        AlertRule rule = getById(id);
        log.info("[spring-watch: 删除告警规则 - id={}, rule={}]", id, rule.getRuleName());
        alertRuleRepository.delete(rule);
    }

    public List<AlertHistory> listAlertHistory(String appName) {
        List<AlertHistory> history;
        if (appName != null) {
            history = alertHistoryRepository.findByAppAppNameOrderByCreatedAtDesc(appName);
        } else {
            history = alertHistoryRepository.findAll();
        }
        log.debug("[spring-watch: 查询告警历史 - app={}, count={}]", appName, history.size());
        return history;
    }
}