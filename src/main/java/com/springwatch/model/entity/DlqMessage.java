package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "dlq_message")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DlqMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_topic", nullable = false, length = 128)
    private String sourceTopic;

    @Column(name = "original_partition")
    private Integer originalPartition;

    @Column(name = "original_offset")
    private Long originalOffset;

    @Column(name = "original_timestamp")
    private Instant originalTimestamp;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(length = 256)
    private String key;

    @Column(name = "error_fqcn", length = 256)
    private String errorFqcn;

    @Column(name = "error_cause_fqcn", length = 256)
    private String errorCauseFqcn;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_stacktrace", columnDefinition = "TEXT")
    private String errorStacktrace;

    @Builder.Default
    private Boolean replayed = false;

    private Instant replayedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
