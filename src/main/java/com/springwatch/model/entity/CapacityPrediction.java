package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "capacity_prediction")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapacityPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long appid;

    @Column(name = "app_name", length = 128)
    private String appName;

    @Column(nullable = false, length = 128)
    private String metric;

    @Column(name = "horizon_hours")
    @Builder.Default
    private Integer horizonHours = 24;

    @Column(name = "current_value")
    private Double currentValue;

    @Column(name = "predicted_value")
    private Double predictedValue;

    @Column(name = "growth_rate")
    private Double growthRate;

    @Column(length = 16)
    private String confidence;

    @Column(length = 32)
    private String scenario;

    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
    }
}
