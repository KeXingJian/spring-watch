package com.springwatch.repository;

import com.springwatch.model.entity.MonitorApp;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MonitorAppRepository extends JpaRepository<MonitorApp, Long> {

    Optional<MonitorApp> findByAppid(Long appid);

    List<MonitorApp> findAllByAppidIn(Collection<Long> appids);

    Page<MonitorApp> findByStatusIgnoreCase(String status, Pageable pageable);

    List<MonitorApp> findByStatusIgnoreCase(String status);
}
