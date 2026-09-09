package com.springwatch.ai.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 编排层 - 统一 LLM 调用器。
 * 收敛所有技能的 LLM 调用样板:重试、空回复检测、降级、日志。
 * 保证不抛异常,失败时返回 fallback(调用方不再各自 try/catch)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmInvoker {

    private static final int MAX_ATTEMPTS = 2;

    private final ChatClient aiChatClient;

    /**
     * 统一 LLM 调用。
     *
     * @param task         任务名(用于日志)
     * @param systemPrompt 系统提示词,为空则跳过
     * @param userPrompt   用户提示词(证据)
     * @param fallback     LLM 失败/空回复时的降级文本
     * @return content + success 标记(供需要区分 degraded 状态的调用方)
     */
    public LlmResult invoke(String task, String systemPrompt, String userPrompt, String fallback) {
        String lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String content;
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    content = aiChatClient.prompt().system(systemPrompt).user(userPrompt).call().content();
                } else {
                    content = aiChatClient.prompt().user(userPrompt).call().content();
                }
                if (content != null && !content.isBlank()) {
                    log.info("[kxj: LLM调用成功 - task={}, attempt={}, len={}]", task, attempt, content.length());
                    return new LlmResult(content, true, null);
                }
                lastError = "空回复";
                log.warn("[kxj: LLM空回复 - task={}, attempt={}]", task, attempt);
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("[kxj: LLM调用失败 - task={}, attempt={}, error={}]", task, attempt, e.getMessage());
            }
        }
        log.warn("[kxj: LLM调用降级 - task={}, error={}]", task, lastError);
        return new LlmResult(fallback == null ? "" : fallback, false, lastError);
    }

    public record LlmResult(String content, boolean success, String errorMsg) {
    }
}
