package com.springwatch.ai.rag;

import com.springwatch.model.entity.KnowledgeChunk;
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
 * P2 RAG - 文档切片 + embedding 入库。
 * embedding 走远端 OpenAI 兼容 API(text-embedding),切片默认按 ~500 字符 + 50 重叠。
 * 向量以 JSON 数组字面量(TEXT)入库,检索时 PGVector 强转。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocEmbeddingService {

    private final KnowledgeChunkRepository chunkRepository;

    @Value("${spring-ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring-ai.openai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${spring-ai.openai.embedding.model:text-embedding-3-small}")
    private String embeddingModel;

    @Value("${spring-watch.ai.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${spring-watch.ai.rag.chunk-overlap:50}")
    private int chunkOverlap;

    /**
     * 将文档切片并 embedding 入库。
     */
    public int ingestDocument(String source, String sourceId, String title, String content) {
        if (content == null || content.isBlank()) {
            log.warn("[kxj: RAG 文档为空,跳过 - source={}, sourceId={}]", source, sourceId);
            return 0;
        }
        List<String> chunks = split(content);
        int saved = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String vector = null;
            try {
                vector = embed(chunk);
            } catch (Exception e) {
                log.warn("[kxj: RAG embedding 失败,入库不带向量 - source={}, idx={}, error={}]",
                        source, i, e.getMessage());
            }
            try {
                chunkRepository.save(KnowledgeChunk.builder()
                        .source(source)
                        .sourceId(sourceId)
                        .title(title)
                        .chunkIndex(i)
                        .content(chunk)
                        .embedding(vector)
                        .build());
                saved++;
            } catch (Exception e) {
                log.warn("[kxj: RAG 切片落库失败 - source={}, idx={}, error={}]", source, i, e.getMessage());
            }
        }
        log.info("[kxj: RAG 文档入库 - source={}, sourceId={}, chunks={}, saved={}]",
                source, sourceId, chunks.size(), saved);
        return saved;
    }

    /** 文本切片:按 chunkSize 切,重叠 chunkOverlap */
    public List<String> split(String content) {
        String clean = content.replace("\r\n", "\n").trim();
        List<String> chunks = new ArrayList<>();
        if (clean.length() <= chunkSize) {
            chunks.add(clean);
            return chunks;
        }
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(start + chunkSize, clean.length());
            if (end < clean.length()) {
                int nl = clean.lastIndexOf('\n', end);
                if (nl > start + chunkSize / 2) {
                    end = nl;
                }
            }
            chunks.add(clean.substring(start, end).trim());
            if (end >= clean.length()) break;
            start = Math.max(end - chunkOverlap, start + 1);
        }
        return chunks;
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
        List<Double> vec = resp.data().getFirst().embedding();
        return vec.toString();
    }

    private record EmbeddingReq(String model, String input) {
    }

    private record EmbeddingResp(List<EmbeddingData> data) {
    }

    private record EmbeddingData(List<Double> embedding) {
    }
}
