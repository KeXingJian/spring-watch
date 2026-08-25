package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String notifyResult;

    private Instant resolvedAt;

    @Column(name = "agg_group_id", length = 32)
    private String aggGroupId;

    @Column(name = "agg_role", length = 16)
    @Builder.Default
    private String aggRole = "leader";

    @Column(name = "agg_suppressed")
    @Builder.Default
    private Boolean aggSuppressed = false;

    @Column(name = "agg_group_count")
    @Builder.Default
    private Integer aggGroupCount = 1;

    @Column(name = "agg_suppressed_count")
    @Builder.Default
    private Integer aggSuppressedCount = 0;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
    }
}