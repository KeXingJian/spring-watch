package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "alert_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id")
    private AlertRule rule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appid", referencedColumnName = "appid")
    private MonitorApp app;

    @Column(length = 16)
    private String alertLevel;

    @Column(columnDefinition = "TEXT")
    private String alertMessage;

    @Column(columnDefinition = "jsonb")
    private String notifyResult;

    private Instant resolvedAt;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
    }
}