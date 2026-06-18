package com.springwatch.repository;

import com.springwatch.model.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    List<AlertRule> findByStatus(String status);

    List<AlertRule> findByRuleTypeAndStatus(String ruleType, String status);
}