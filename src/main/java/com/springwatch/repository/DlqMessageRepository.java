package com.springwatch.repository;

import com.springwatch.model.entity.DlqMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DlqMessageRepository extends JpaRepository<DlqMessage, Long> {

    List<DlqMessage> findByReplayedFalseOrderByCreatedAtDesc();

    List<DlqMessage> findBySourceTopicAndReplayedFalseOrderByCreatedAtDesc(String sourceTopic);

    @Modifying
    @Query("update DlqMessage d set d.replayed = true, d.replayedAt = :ts where d.id = :id")
    int markReplayed(@Param("id") Long id, @Param("ts") Instant ts);
}
