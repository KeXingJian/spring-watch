package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "monitor_app")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitorApp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private Long appid;

    @Column(nullable = false, unique = true, length = 128)
    private String appName;

    @Column(length = 200)
    private String endpoint;

    @Column(name = "metrics_port")
    @Builder.Default
    private Integer metricsPort = 9464;

    @Builder.Default
    private Integer scrapeInterval = 30;

    @Column(name = "schedule_type", length = 16)
    @Builder.Default
    private String scheduleType = "INTERVAL";

    @Column(name = "cron_expression", length = 64)
    private String cronExpression;

    private String labels;

    @Column(length = 16)
    @Builder.Default
    private String status = "active";

    private Instant lastHeartbeat;

    private Instant lastLogPullTime;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
