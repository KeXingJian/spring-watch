package com.springwatch.repository;

import com.springwatch.model.entity.MonitorApp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MonitorAppRepository extends JpaRepository<MonitorApp, Long> {

    Optional<MonitorApp> findByAppName(String appName);

    Optional<MonitorApp> findByAppid(Long appid);

    List<MonitorApp> findByStatus(String status);

    boolean existsByAppName(String appName);

    List<MonitorApp> findAllByAppidIn(Collection<Long> appids);

}