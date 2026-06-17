package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "log_dedup_count",
        uniqueConstraints = @UniqueConstraint(name = "uk_log_dedup_appid_fp", columnNames = {"appid", "fingerprint"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogDedupCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long appid;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "dedup_count", nullable = false)
    @Builder.Default
    private Long dedupCount = 0L;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (lastSeenAt == null) lastSeenAt = now;
        if (dedupCount == null) dedupCount = 0L;
    }
}
