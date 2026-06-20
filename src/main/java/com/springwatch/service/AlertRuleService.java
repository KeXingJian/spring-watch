package com.springwatch.service;

import com.springwatch.alerter.AlertRuleCache;
import com.springwatch.model.dto.AlertRuleRequest;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.AlertRuleRepository;
import com.springwatch.repository.MonitorAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private final AlertRuleRepository alertRuleRepository;
    private final MonitorAppRepository monitorAppRepository;
    private final AlertRuleCache ruleCache;

    public List<AlertRule> listAll() {
        return alertRuleRepository.findAll();
    }

    public List<AlertRule> listByAppid(Long appid) {
        return alertRuleRepository.findAll().stream()
                .filter(r -> r.getApp() != null && r.getApp().getAppid().equals(appid))
                .toList();
    }

    public Optional<AlertRule> findById(Long id) {
        return alertRuleRepository.findById(id);
    }

    @Transactional
    public AlertRule create(AlertRuleRequest req) {
        MonitorApp app = monitorAppRepository.findByAppid(req.getAppid())
                .orElseThrow(() -> new IllegalArgumentException("appid 不存在: " + req.getAppid()));
        AlertRule rule = AlertRule.builder()
                .app(app)
                .ruleName(req.getRuleName())
                .ruleType(req.getRuleType())
                .expression(req.getExpression())
                .thresholdValue(req.getThresholdValue())
                .durationSeconds(req.getDurationSeconds() == null ? 60 : req.getDurationSeconds())
                .notifyChannels(req.getNotifyChannels())
                .status(req.getStatus() == null ? "enabled" : req.getStatus())
                .level(req.getLevel() == null ? "warning" : req.getLevel())
                .times(req.getTimes() == null ? 1 : req.getTimes())
                .template(req.getTemplate())
                .build();
        AlertRule saved = alertRuleRepository.save(rule);
        ruleCache.refresh();
        log.info("[spring-watch: 告警规则创建 - id={}, name={}, appid={}]", saved.getId(), saved.getRuleName(), req.getAppid());
        return saved;
    }

    @Transactional
    public AlertRule update(Long id, AlertRuleRequest req) {
        AlertRule rule = alertRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在: " + id));
        if (req.getAppid() != null) {
            MonitorApp app = monitorAppRepository.findByAppid(req.getAppid())
                    .orElseThrow(() -> new IllegalArgumentException("appid 不存在: " + req.getAppid()));
            rule.setApp(app);
        }
        if (req.getRuleName() != null) rule.setRuleName(req.getRuleName());
        if (req.getRuleType() != null) rule.setRuleType(req.getRuleType());
        if (req.getExpression() != null) rule.setExpression(req.getExpression());
        if (req.getThresholdValue() != null) rule.setThresholdValue(req.getThresholdValue());
        if (req.getDurationSeconds() != null) rule.setDurationSeconds(req.getDurationSeconds());
        if (req.getNotifyChannels() != null) rule.setNotifyChannels(req.getNotifyChannels());
        if (req.getStatus() != null) rule.setStatus(req.getStatus());
        if (req.getLevel() != null) rule.setLevel(req.getLevel());
        if (req.getTimes() != null) rule.setTimes(req.getTimes());
        if (req.getTemplate() != null) rule.setTemplate(req.getTemplate());
        AlertRule saved = alertRuleRepository.save(rule);
        ruleCache.refresh();
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        if (!alertRuleRepository.existsById(id)) {
            throw new IllegalArgumentException("规则不存在: " + id);
        }
        alertRuleRepository.deleteById(id);
        ruleCache.refresh();
    }

    @Transactional
    public AlertRule toggle(Long id) {
        AlertRule rule = alertRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在: " + id));
        rule.setStatus("enabled".equalsIgnoreCase(rule.getStatus()) ? "disabled" : "enabled");
        AlertRule saved = alertRuleRepository.save(rule);
        ruleCache.refresh();
        return saved;
    }
}
