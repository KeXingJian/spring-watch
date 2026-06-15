package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "alert_notification_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertNotificationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long appid;

    @Column(nullable = false, length = 256)
    private String target;

    @Column(length = 16)
    @Builder.Default
    private String status = "enabled";

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
