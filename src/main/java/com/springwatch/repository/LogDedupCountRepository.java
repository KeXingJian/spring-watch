package com.springwatch.repository;

import com.springwatch.model.entity.LogDedupCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface LogDedupCountRepository extends JpaRepository<LogDedupCount, Long> {


    @Modifying
    @Query(value = """
            INSERT INTO log_dedup_count (appid, fingerprint, dedup_count, last_seen_at, created_at)
            VALUES (:appid, :fingerprint, :count, :now, :now)
            ON CONFLICT (appid, fingerprint)
            DO UPDATE SET dedup_count = log_dedup_count.dedup_count + EXCLUDED.dedup_count,
                          last_seen_at = EXCLUDED.last_seen_at
            """, nativeQuery = true)
    int upsertAddCount(@Param("appid") Long appid,
                        @Param("fingerprint") String fingerprint,
                        @Param("count") long count,
                        @Param("now") Instant now);

    @Query("SELECT d FROM LogDedupCount d WHERE d.appid = :appid ORDER BY d.dedupCount DESC, d.lastSeenAt DESC")
    List<LogDedupCount> findTopByAppidOrderByCount(@Param("appid") Long appid,
                                                   org.springframework.data.domain.Pageable pageable);

    /**
     * 批量查若干 fingerprint 的去重计数,返回 fingerprint -> dedup_count 映射(没有返回 0)
     * 给 topFingerprints 补真实总次数用
     */
    @Query("SELECT d.fingerprint AS fp, d.dedupCount AS cnt FROM LogDedupCount d " +
            "WHERE d.appid = :appid AND d.fingerprint IN :fps")
    List<FpCount> sumDedupByFingerprints(@Param("appid") Long appid,
                                          @Param("fps") Collection<String> fps);

    interface FpCount {
        String getFp();
        Long getCnt();
    }
}
