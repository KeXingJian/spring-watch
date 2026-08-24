package com.springwatch.ai.web;

import com.springwatch.ai.service.AiChatService;
import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.entity.ChatConversation;
import com.springwatch.model.entity.ChatMessage;
import com.springwatch.repository.ChatConversationRepository;
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

    private final AiChatService aiChatService;
    private final ChatConversationRepository conversationRepository;

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
                ? aiChatService.createConversation(null).getId()
                : req.conversationId();
        return aiChatService.chat(convId, req.message());
    }

    @PostMapping("/conversations")
    public ApiResponse<ChatConversation> createConversation(@RequestBody(required = false) CreateConversationRequest req) {
        String title = req == null ? null : req.title();
        ChatConversation conv = aiChatService.createConversation(title);
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
        List<ChatMessage> messages = aiChatService.listMessages(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", messages.size());
        out.put("rows", messages);
        return ApiResponse.ok(out);
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    public record ChatRequest(Long conversationId, String message) {}

    public record CreateConversationRequest(String title) {}
}