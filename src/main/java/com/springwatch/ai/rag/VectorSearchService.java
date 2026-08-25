package com.springwatch.ai.rag;

import com.springwatch.repository.KnowledgeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * P2 RAG - 相似检索服务。
 * 查询文本 → embedding → PGVector 余弦检索 → 返回命中切片。
 * embedding 失败时降级为"无相似片段"(不影响主流程)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private final KnowledgeChunkRepository chunkRepository;

    @Value("${spring-ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring-ai.openai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${spring-ai.openai.embedding.model:text-embedding-3-small}")
    private String embeddingModel;

    public List<Hit> search(String query, int topN) {
        int safeTop = Math.min(Math.max(topN, 1), 10);
        try {
            String vector = embed(query);
            List<Object[]> rows = chunkRepository.findSimilar(vector, safeTop);
            List<Hit> hits = new ArrayList<>();
            for (Object[] r : rows) {
                // 列序: id, source, source_id, title, chunk_index, content, embedding, created_at, similarity
                Number sim = (Number) r[8];
                hits.add(new Hit(
                        r[1] == null ? null : r[1].toString(),
                        r[2] == null ? null : r[2].toString(),
                        r[3] == null ? null : r[3].toString(),
                        r[5] == null ? null : r[5].toString(),
                        sim == null ? 0.0 : sim.doubleValue()));
            }
            log.debug("[kxj: RAG 相似检索 - query={}, hits={}]", truncate(query, 50), hits.size());
            return hits;
        } catch (Exception e) {
            log.warn("[kxj: RAG 相似检索失败 - error={}]", e.getMessage());
            return List.of();
        }
    }

    private String embed(String text) {
        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        EmbeddingResp resp = client.post()
                .uri("/v1/embeddings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbeddingReq(embeddingModel, text))
                .retrieve()
                .body(EmbeddingResp.class);
        if (resp == null || resp.data() == null || resp.data().isEmpty()) {
            throw new IllegalStateException("embedding 返回为空");
        }
        return resp.data().getFirst().embedding().toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record EmbeddingReq(String model, String input) {
    }

    private record EmbeddingResp(List<EmbeddingData> data) {
    }

    private record EmbeddingData(List<Double> embedding) {
    }

    public record Hit(String source, String sourceId, String title, String content, double similarity) {
    }
}
