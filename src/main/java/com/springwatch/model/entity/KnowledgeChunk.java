package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "knowledge_chunk")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "source_id", length = 128)
    private String sourceId;

    @Column(length = 256)
    private String title;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 向量字面量(JSON数组字符串),如 "[0.1,0.2,...]" */
    @Column(columnDefinition = "TEXT")
    private String embedding;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
    }
}
