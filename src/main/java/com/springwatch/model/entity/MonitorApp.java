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

    @Column(nullable = false, unique = true, length = 128)
    private String appName;

    @Column(length = 200)
    private String endpoint;

    @Column(length = 32)
    @Builder.Default
    private String appType = "springboot";

    @Builder.Default
    private Integer scrapeInterval = 15;

    private String labels;

    @Column(length = 16)
    @Builder.Default
    private String status = "active";

    private Instant lastHeartbeat;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}