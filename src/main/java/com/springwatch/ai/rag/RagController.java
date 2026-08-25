package com.springwatch.ai.rag;

import com.springwatch.model.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final DocEmbeddingService docEmbeddingService;
    private final VectorSearchService vectorSearchService;

    /**
     * 相似检索。
     * GET /api/rag/search?q=OOM&top=5
     */
    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> search(
            @RequestParam("q") String q,
            @RequestParam(defaultValue = "5") int top) {
        log.info("[kxj: RAG 检索请求 - q={}, top={}]", truncate(q, 50), top);
        List<VectorSearchService.Hit> hits = vectorSearchService.search(q, top);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", hits.size());
        out.put("hits", hits);
        return ApiResponse.ok(out);
    }

    /**
     * 文档入库(文本)。
     * POST /api/rag/ingest  body: { source, sourceId, title, content }
     */
    @PostMapping("/ingest")
    public ApiResponse<Map<String, Object>> ingest(@RequestBody IngestRequest req) {
        if (req == null || req.source() == null || req.source().isBlank()) {
            return ApiResponse.fail(400, "source 必填");
        }
        log.info("[kxj: RAG 文档入库 - source={}, sourceId={}, title={}]",
                req.source(), req.sourceId(), req.title());
        int saved = docEmbeddingService.ingestDocument(
                req.source(), req.sourceId(), req.title(), req.content());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("savedChunks", saved);
        return ApiResponse.ok(out);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public record IngestRequest(String source, String sourceId, String title, String content) {
    }
}