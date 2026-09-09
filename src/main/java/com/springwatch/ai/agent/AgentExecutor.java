package com.springwatch.ai.agent;

import com.springwatch.model.dto.ChatStreamChunk;
import com.springwatch.model.entity.ChatConversation;
import com.springwatch.model.entity.ChatMessage;
import com.springwatch.repository.ChatConversationRepository;
import com.springwatch.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 编排层 - Agent 执行器(chat 主入口)。
 * 会话生命周期 + 分层记忆 + 技能发现 + 工具调用循环(Spring AI 2.0 advisor 链):
 * 用户消息落库 → MemoryManager 组装上下文(中期摘要+短期窗口) →
 * ChatClient 携带 defaultTools(数据查询工具+技能调用工具)流式作答 → 回复落库。
 * 技能列表注入系统提示词,LLM 可自主通过 SkillInvokeTool 调用运维技能。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutor {

    private final ChatClient aiChatClient;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final AgentRegistry agentRegistry;
    private final MemoryManager memoryManager;

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
     * 流式对话(SSE 事件流):保存 user 消息 → 分层记忆组装 → LLM 流式输出(带工具/技能) → 落库 assistant 回复。
     * 事件类型参考 HertzBeat ChatResponseChunk:message 增量 / complete 完成(回传 assistantMessageId) / error 失败。
     * LLM 异常时降级返回错误事件,不阻塞。
     */
    public Flux<ChatStreamChunk> chat(Long conversationId, String userMessage) {
        ChatConversation conv = conversationRepository.findById(conversationId)
                .orElseGet(() -> createConversation(null));
        ChatMessage userMsg = saveMessage(conv, "user", userMessage);
        Long excludeId = userMsg == null ? null : userMsg.getId();

        List<Message> history = memoryManager.buildContext(conversationId, excludeId);
        StringBuilder full = new StringBuilder();

        return aiChatClient.prompt()
                .system(buildSystemPrompt())
                .messages(history)
                .user(userMessage)
                .stream()
                .content()
                .doOnNext(full::append)
                .map(chunk -> ChatStreamChunk.message(conv.getId(), chunk))
                .concatWith(Flux.defer(() -> {
                    ChatMessage saved = saveMessage(conv, "assistant", full.toString());
                    log.info("[kxj: AI对话完成 - conversationId={}, replyLen={}, assistantMessageId={}]",
                            conv.getId(), full.length(), saved == null ? null : saved.getId());
                    return Flux.just(ChatStreamChunk.complete(conv.getId(), saved == null ? null : saved.getId()));
                }))
                .doOnError(e -> {
                    log.warn("[kxj: AI对话失败 - conversationId={}, error={}]", conv.getId(), e.getMessage());
                    saveMessage(conv, "assistant", "诊断失败:" + e.getMessage() + "(LLM 服务不可用,可稍后重试)");
                })
                .onErrorResume(e -> Flux.just(ChatStreamChunk.error(conv.getId(),
                        "诊断失败:" + e.getMessage() + " (LLM 服务不可用,可稍后重试)")));
    }

    /** 技能注册表透传:SOP 定时/手动执行、HTTP 技能接口共用 */
    public String runSkill(String skillName, Long appid) {
        return agentRegistry.run(skillName, appid);
    }

    public List<AgentSkill> listSkills() {
        return agentRegistry.all();
    }

    private String buildSystemPrompt() {
        String skillList = agentRegistry.skillListText();
        return skillList.isBlank()
                ? systemPrompt
                : systemPrompt + "\n\n【平台运维技能】可调用技能工具执行:\n" + skillList;
    }

    private ChatMessage saveMessage(ChatConversation conv, String role, String content) {
        try {
            ChatMessage saved = messageRepository.save(ChatMessage.builder()
                    .conversation(conv)
                    .role(role)
                    .content(content)
                    .build());
            return saved;
        } catch (Exception e) {
            log.warn("[kxj: AI消息落库失败 - conversationId={}, role={}, error={}]", conv.getId(), role, e.getMessage());
            return null;
        }
    }
}
