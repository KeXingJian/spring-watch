package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "diagnosis_report")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_history_id")
    private Long alertHistoryId;

    @Column(nullable = false)
    private Long appid;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "rule_name", length = 256)
    private String ruleName;

    @Column(name = "alert_level", length = 16)
    private String alertLevel;

    @Column(name = "trigger_metric", length = 128)
    private String triggerMetric;

    @Column(name = "trigger_value")
    private Double triggerValue;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(columnDefinition = "TEXT")
    private String report;

    @Column(length = 16)
    @Builder.Default
    private String status = "success";

    @Column(name = "error_msg", length = 512)
    private String errorMsg;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
    }
}