package com.springwatch.repository;

import com.springwatch.model.entity.CapacityPrediction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapacityPredictionRepository extends JpaRepository<CapacityPrediction, Long> {

    Page<CapacityPrediction> findByAppidOrderByCreatedAtDesc(Long appid, Pageable pageable);
}
