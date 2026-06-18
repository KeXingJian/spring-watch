package com.springwatch.repository;

import com.springwatch.model.entity.AlertHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AlertHistoryRepository extends JpaRepository<AlertHistory, Long> {

    List<AlertHistory> findByAppAppidAndRuleIdAndResolvedAtIsNullOrderByCreatedAtDesc(Long appid, Long ruleId);

}