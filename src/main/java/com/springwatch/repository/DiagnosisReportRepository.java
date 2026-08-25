package com.springwatch.repository;

import com.springwatch.model.entity.DiagnosisReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface DiagnosisReportRepository extends JpaRepository<DiagnosisReport, Long> {

    Page<DiagnosisReport> findByAppidOrderByCreatedAtDesc(Long appid, Pageable pageable);

    Optional<DiagnosisReport> findFirstByAppidAndCreatedAtAfterOrderByCreatedAtDesc(Long appid, Instant after);
}