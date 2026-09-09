package com.springwatch.ai.agent;

import com.springwatch.model.entity.ChatConversation;
import com.springwatch.model.entity.ChatMessage;
import com.springwatch.repository.ChatConversationRepository;
import com.springwatch.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排层 - 分层记忆管理。
 * 短期:最近 N 条 user/assistant 消息(窗口);
 * 中期:窗口外历史压缩为 LLM 摘要(role=summary),压缩后删除旧行防膨胀;
 * 长期:由 RAG 知识库承接,不在此层。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryManager {

    private static final String SUMMARY_ROLE = "summary";

    private final ChatMessageRepository messageRepository;
    private final ChatConversationRepository conversationRepository;
    private final LlmInvoker llmInvoker;

    @Value("${ai.agent.short-term-window:12}")
    private int shortTermWindow;

    @Value("${ai.agent.compact-threshold:24}")
    private int compactThreshold;

    /**
     * 组装上下文: [中期摘要] + 最近 N 条普通消息。
     *
     * @param conversationId   会话 id
     * @param excludeMessageId 需排除的消息 id(刚落库的当前 user 消息,由调用方单独发送)
     */
    public List<Message> buildContext(Long conversationId, Long excludeMessageId) {
        List<ChatMessage> all = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<ChatMessage> history = all.stream()
                .filter(m -> !m.getId().equals(excludeMessageId))
                .toList();
        if (history.isEmpty()) {
            return List.of();
        }
        long regularCount = history.stream().filter(m -> !SUMMARY_ROLE.equals(m.getRole())).count();
        if (regularCount > Math.max(compactThreshold, shortTermWindow * 2)) {
            compact(conversationId, history);
            history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                    .filter(m -> !m.getId().equals(excludeMessageId))
                    .toList();
        }

        ChatMessage latestSummary = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (SUMMARY_ROLE.equals(history.get(i).getRole())) {
                latestSummary = history.get(i);
                break;
            }
        }
        List<Message> out = new ArrayList<>();
        if (latestSummary != null) {
            out.add(new SystemMessage("【早期对话记忆摘要】\n" + latestSummary.getContent()));
        }
        List<ChatMessage> regular = history.stream()
                .filter(m -> !SUMMARY_ROLE.equals(m.getRole()))
                .toList();
        int from = Math.max(0, regular.size() - Math.max(shortTermWindow, 1));
        for (int i = from; i < regular.size(); i++) {
            ChatMessage m = regular.get(i);
            if ("user".equals(m.getRole())) {
                out.add(new UserMessage(m.getContent()));
            } else if ("assistant".equals(m.getRole())) {
                out.add(new AssistantMessage(m.getContent()));
            }
        }
        return out;
    }

    /** 压缩窗口外历史为一条摘要:删除旧行,落库 role=summary */
    private void compact(Long conversationId, List<ChatMessage> history) {
        List<ChatMessage> regular = history.stream()
                .filter(m -> !SUMMARY_ROLE.equals(m.getRole()))
                .toList();
        int boundary = Math.max(0, regular.size() - Math.max(shortTermWindow, 1));
        if (boundary == 0) {
            return;
        }
        List<ChatMessage> oldOnes = regular.subList(0, boundary);
        StringBuilder text = new StringBuilder();
        for (ChatMessage m : oldOnes) {
            String label = switch (m.getRole() == null ? "" : m.getRole()) {
                case "user" -> "用户: ";
                case "assistant" -> "助手: ";
                default -> "消息: ";
            };
            text.append(label).append(m.getContent()).append('\n');
        }
        String prompt = "请将以下历史对话压缩为简明记忆摘要(200字内),保留关键事实:问题现象、根因结论、处置结果:\n\n"
                + truncate(text.toString(), 8000);
        LlmInvoker.LlmResult r = llmInvoker.invoke("对话记忆压缩", null, prompt, truncate(text.toString(), 1500));
        ChatConversation conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv == null) {
            return;
        }
        messageRepository.deleteAll(oldOnes);
        messageRepository.save(ChatMessage.builder()
                .conversation(conv)
                .role(SUMMARY_ROLE)
                .content(r.content())
                .build());
        log.info("[kxj: 对话记忆压缩 - conversationId={}, 压缩条数={}, 摘要len={}, success={}]",
                conversationId, oldOnes.size(), r.content().length(), r.success());
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
