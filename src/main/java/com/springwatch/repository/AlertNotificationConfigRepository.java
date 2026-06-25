package com.springwatch.repository;

import com.springwatch.model.entity.AlertNotificationConfig;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertNotificationConfigRepository extends JpaRepository<AlertNotificationConfig, Long> {

    List<AlertNotificationConfig> findByAppidAndStatus(Long appid, String status);

    Page<AlertNotificationConfig> findByAppid(Long appid, Pageable pageable);

    Page<AlertNotificationConfig> findAll(Pageable pageable);
}
