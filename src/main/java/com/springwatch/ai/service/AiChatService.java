package com.springwatch.ai.service;

import com.springwatch.model.entity.ChatConversation;
import com.springwatch.model.entity.ChatMessage;
import com.springwatch.repository.ChatConversationRepository;
import com.springwatch.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private static final int MAX_HISTORY_MESSAGES = 12;

    private final ChatClient aiChatClient;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;

    @Value("${ai.chat.system-prompt}")
    private String systemPrompt;

    public ChatConversation createConversation(String title) {
        ChatConversation conv = ChatConversation.builder()
                .title(title == null || title.isBlank() ? "新会话" : title)
                .build();
        ChatConversation saved = conversationRepository.save(conv);
        log.info("[kxj: AI会话创建 - id={}, title={}]", saved.getId(), saved.getTitle());
        return saved;
    }

    public List<ChatMessage> listMessages(Long conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /**
     * 流式对话:保存 user 消息 → 组装历史 → LLM 流式输出 → 结束后落库 assistant 完整回复
     * LLM 异常时降级返回错误提示,不阻塞
     */
    public Flux<String> chat(Long conversationId, String userMessage) {
        ChatConversation conv = conversationRepository.findById(conversationId)
                .orElseGet(() -> createConversation(null));
        saveMessage(conv, "user", userMessage);

        List<Message> history = buildHistory(conversationId);
        StringBuilder full = new StringBuilder();

        return aiChatClient.prompt()
                .system(systemPrompt)
                .messages(history)
                .user(userMessage)
                .stream()
                .content()
                .doOnNext(full::append)
                .doOnComplete(() -> {
                    saveMessage(conv, "assistant", full.toString());
                    log.info("[kxj: AI对话完成 - conversationId={}, replyLen={}]", conv.getId(), full.length());
                })
                .doOnError(e -> {
                    log.warn("[kxj: AI对话失败 - conversationId={}, error={}]", conv.getId(), e.getMessage());
                    saveMessage(conv, "assistant", "诊断失败:" + e.getMessage() + "(LLM 服务不可用,可稍后重试)");
                })
                .onErrorResume(e -> Flux.just("\n\n[诊断失败]" + e.getMessage() + " (LLM 服务不可用,可稍后重试)"));
    }

    private void saveMessage(ChatConversation conv, String role, String content) {
        try {
            messageRepository.save(ChatMessage.builder()
                    .conversation(conv)
                    .role(role)
                    .content(content)
                    .build());
        } catch (Exception e) {
            log.warn("[kxj: AI消息落库失败 - conversationId={}, role={}, error={}]", conv.getId(), role, e.getMessage());
        }
    }

    private List<Message> buildHistory(Long conversationId) {
        List<ChatMessage> recent = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        // 当前 user 消息已落库(最后一条),排除避免重复发送
        List<ChatMessage> slice = recent.isEmpty() ? recent : recent.subList(0, recent.size() - 1);
        int from = Math.max(0, slice.size() - MAX_HISTORY_MESSAGES);
        List<Message> history = new ArrayList<>();
        for (int i = from; i < slice.size(); i++) {
            ChatMessage m = slice.get(i);
            if ("user".equals(m.getRole())) {
                history.add(new UserMessage(m.getContent()));
            } else if ("assistant".equals(m.getRole())) {
                history.add(new AssistantMessage(m.getContent()));
            }
        }
        return history;
    }
}