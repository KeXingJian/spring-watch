package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "sop_schedule")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SopSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sop_name", nullable = false, length = 64)
    private String sopName;

    private Long appid;

    @Column(name = "cron_expression", nullable = false, length = 64)
    private String cronExpression;

    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "last_run_time")
    private Instant lastRunTime;

    @Column(name = "next_run_time")
    private Instant nextRunTime;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
    }
}