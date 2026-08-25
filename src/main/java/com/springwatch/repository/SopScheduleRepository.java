package com.springwatch.repository;

import com.springwatch.model.entity.SopSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SopScheduleRepository extends JpaRepository<SopSchedule, Long> {

    List<SopSchedule> findByEnabledTrue();
}