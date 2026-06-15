package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "alert_rule")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appid", referencedColumnName = "appid")
    private MonitorApp app;

    @Column(length = 256)
    private String ruleName;

    @Column(length = 32)
    private String ruleType;

    @Column(length = 512)
    private String expression;

    private Double thresholdValue;

    @Builder.Default
    private Integer durationSeconds = 60;

    @Column(columnDefinition = "jsonb")
    private String notifyChannels;

    @Column(length = 16)
    @Builder.Default
    private String status = "enabled";

    @Column(length = 16)
    @Builder.Default
    private String level = "warning";

    @Column
    @Builder.Default
    private Integer times = 1;

    @Column(length = 1024)
    private String template;

    @Column(updatable = false)
    private Instant createdAt;
}