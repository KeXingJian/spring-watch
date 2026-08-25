package com.springwatch.repository;

import com.springwatch.model.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    /**
     * PGVector 余弦相似检索:embedding 存 TEXT 字面量,这里用 ::vector 强转,
     * 按余弦距离升序返回 topN。embedding 为 null 的行用 1 - ... 无法比较,先过滤。
     */
    @Query(value = """
            SELECT id, source, source_id, title, chunk_index, content, embedding, created_at,
                   1 - (embedding::vector <=> CAST(:query AS vector)) AS similarity
            FROM knowledge_chunk
            WHERE embedding IS NOT NULL AND embedding != ''
            ORDER BY embedding::vector <=> CAST(:query AS vector)
            LIMIT :topN
            """, nativeQuery = true)
    List<Object[]> findSimilar(@Param("query") String queryVector, @Param("topN") int topN);
}
