package com.springwatch.service;

import com.springwatch.alerter.AlertRuleCache;
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
    private final AlertRuleCache ruleCache;

    @Transactional
    public AlertRule createRule(Long appid, String ruleName, String ruleType,
                                 String expression, Double thresholdValue, Integer durationSeconds,
                                 String notifyChannels) {
        MonitorApp app = monitorAppRepository.findByAppid(appid)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: appid=" + appid));

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
        ruleCache.refresh();
        log.info("[spring-watch: 创建告警规则 - appid={}, app={}, rule={}, type={}]",
                appid, app.getAppName(), ruleName, ruleType);
        return saved;
    }

    public List<AlertRule> listRules(Long appid) {
        List<AlertRule> rules;
        if (appid != null) {
            rules = alertRuleRepository.findByAppAppidAndStatus(appid, "enabled");
        } else {
            rules = alertRuleRepository.findByStatus("enabled");
        }
        log.debug("[spring-watch: 查询告警规则 - appid={}, count={}]", appid, rules.size());
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
        AlertRule saved = alertRuleRepository.save(rule);
        ruleCache.refresh();
        log.info("[spring-watch: 更新告警规则 - id={}, rule={}]", id, saved.getRuleName());
        return saved;
    }

    @Transactional
    public void deleteRule(Long id) {
        AlertRule rule = getById(id);
        alertRuleRepository.delete(rule);
        ruleCache.refresh();
        log.info("[spring-watch: 删除告警规则 - id={}, rule={}]", id, rule.getRuleName());
    }

    public List<AlertHistory> listAlertHistory(Long appid) {
        List<AlertHistory> history;
        if (appid != null) {
            history = alertHistoryRepository.findByAppAppidOrderByCreatedAtDesc(appid);
        } else {
            history = alertHistoryRepository.findAll();
        }
        log.debug("[spring-watch: 查询告警历史 - appid={}, count={}]", appid, history.size());
        return history;
    }
}