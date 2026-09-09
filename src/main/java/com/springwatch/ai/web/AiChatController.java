package com.springwatch.ai.web;

import com.springwatch.ai.agent.AgentExecutor;
import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.entity.ChatConversation;
import com.springwatch.model.entity.ChatMessage;
import com.springwatch.model.entity.DiagnosisReport;
import com.springwatch.repository.ChatConversationRepository;
import com.springwatch.repository.DiagnosisReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AgentExecutor agentExecutor;
    private final ChatConversationRepository conversationRepository;
    private final DiagnosisReportRepository diagnosisReportRepository;

    /**
     * 流式对话(SSE)。
     * POST /api/ai/chat  body: { conversationId?, message }
     * conversationId 为空时自动新建会话。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest req) {
        if (req == null || req.message() == null || req.message().isBlank()) {
            return Flux.just("消息不能为空");
        }
        log.info("[kxj: AI对话开始 - conversationId={}, message={}]",
                req.conversationId(), truncate(req.message()));
        Long convId = req.conversationId() == null
                ? agentExecutor.createConversation(null).getId()
                : req.conversationId();
        return agentExecutor.chat(convId, req.message());
    }

    @PostMapping("/conversations")
    public ApiResponse<ChatConversation> createConversation(@RequestBody(required = false) CreateConversationRequest req) {
        String title = req == null ? null : req.title();
        ChatConversation conv = agentExecutor.createConversation(title);
        log.info("[kxj: AI会话创建接口 - id={}, title={}]", conv.getId(), conv.getTitle());
        return ApiResponse.ok(conv);
    }

    @GetMapping("/conversations")
    public ApiResponse<Map<String, Object>> listConversations() {
        List<ChatConversation> list = conversationRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(0, 50)).getContent();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("rows", list);
        return ApiResponse.ok(out);
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<Map<String, Object>> listMessages(@PathVariable Long id) {
        List<ChatMessage> messages = agentExecutor.listMessages(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", messages.size());
        out.put("rows", messages);
        return ApiResponse.ok(out);
    }

    /**
     * 运维技能清单(注册表动态返回,前端可展示可调用)。
     * GET /api/ai/skills
     */
    @GetMapping("/skills")
    public ApiResponse<Map<String, Object>> listSkills() {
        List<Map<String, Object>> rows = agentExecutor.listSkills().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", s.name());
                    m.put("description", s.description());
                    return m;
                })
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("rows", rows);
        return ApiResponse.ok(out);
    }

    /**
     * 告警智能诊断报告查询(按 appid 分页,倒序)。
     * GET /api/ai/diagnosis?appid=1&page=0&size=10
     */
    @GetMapping("/diagnosis")
    public ApiResponse<Map<String, Object>> listDiagnosis(
            @RequestParam("appid") Long appid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        List<DiagnosisReport> reports = diagnosisReportRepository
                .findByAppidOrderByCreatedAtDesc(appid, PageRequest.of(Math.max(page, 0), safeSize)).getContent();
        log.info("[kxj: AI诊断报告查询 - appid={}, page={}, size={}, count={}]", appid, page, safeSize, reports.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", reports.size());
        out.put("rows", reports);
        return ApiResponse.ok(out);
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    public record ChatRequest(Long conversationId, String message) {}

    public record CreateConversationRequest(String title) {}
}