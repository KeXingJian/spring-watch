package com.springwatch.service;

import com.springwatch.alerter.AlertNotifier;
import com.springwatch.model.dto.NotificationConfigRequest;
import com.springwatch.model.entity.AlertNotificationConfig;
import com.springwatch.repository.AlertNotificationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationConfigService {

    private final AlertNotificationConfigRepository repository;
    private final AlertNotifier alertNotifier;

    public Page<AlertNotificationConfig> listAll(Pageable pageable) {
        return repository.findAll(pageable);
    }


    public Page<AlertNotificationConfig> listByAppid(Long appid, Pageable pageable) {
        return repository.findByAppid(appid, pageable);
    }


    public Optional<AlertNotificationConfig> findById(Long id) {
        return repository.findById(id);
    }

    @Transactional
    public AlertNotificationConfig create(NotificationConfigRequest req) {
        AlertNotificationConfig cfg = AlertNotificationConfig.builder()
                .appid(req.getAppid())
                .target(req.getTarget())
                .status(req.getStatus() == null ? "enabled" : req.getStatus())
                .build();
        AlertNotificationConfig saved = repository.save(cfg);
        log.info("[spring-watch: 通知配置创建 - id={}, appid={}, target={}]", saved.getId(), saved.getAppid(), saved.getTarget());
        return saved;
    }

    @Transactional
    public AlertNotificationConfig update(Long id, NotificationConfigRequest req) {
        AlertNotificationConfig cfg = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        if (req.getAppid() != null) cfg.setAppid(req.getAppid());
        if (req.getTarget() != null) cfg.setTarget(req.getTarget());
        if (req.getStatus() != null) cfg.setStatus(req.getStatus());
        return repository.save(cfg);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("配置不存在: " + id);
        }
        repository.deleteById(id);
    }

    public String testEmail(String to) {
        return alertNotifier.sendTestEmail(to);
    }
}
