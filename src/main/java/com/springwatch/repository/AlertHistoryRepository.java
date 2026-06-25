package com.springwatch.repository;

import com.springwatch.model.entity.AlertHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AlertHistoryRepository extends JpaRepository<AlertHistory, Long> {

    List<AlertHistory> findByAppAppidAndRuleIdAndResolvedAtIsNullOrderByCreatedAtDesc(Long appid, Long ruleId);

    Page<AlertHistory> findByAppAppid(Long appid, Pageable pageable);

    @Modifying
    @Query("delete from AlertHistory h where h.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
