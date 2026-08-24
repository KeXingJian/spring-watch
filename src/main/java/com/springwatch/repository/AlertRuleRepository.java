package com.springwatch.repository;

import com.springwatch.model.entity.AlertRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    List<AlertRule> findByStatus(String status);

    List<AlertRule> findByRuleTypeAndStatus(String ruleType, String status);

    Page<AlertRule> findByAppAppid(Long appid, Pageable pageable);

    List<AlertRule> findByAppAppid(Long appid);

    /**
     * kxj: 仅取 enabled 规则 id 列表(轻量投影),供 AlertRuleCache 变更检测,
     * 避免每 30s 全量加载规则实体。
     */
    @Query("select r.id from AlertRule r where r.status = :status")
    List<Long> findIdsByStatus(@Param("status") String status);
}
