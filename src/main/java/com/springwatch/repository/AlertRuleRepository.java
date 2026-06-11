package com.springwatch.repository;

import com.springwatch.model.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    List<AlertRule> findByAppAppNameAndStatus(String appName, String status);

    List<AlertRule> findByStatus(String status);
}