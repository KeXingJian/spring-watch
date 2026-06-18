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


}