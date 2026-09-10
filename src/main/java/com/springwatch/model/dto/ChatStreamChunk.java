package com.springwatch.model.dto;

/**
 * AI 对话流式应答事件体(参考 HertzBeat ChatResponseChunk)。
 * 通过 SSE 以 JSON 形式下发,前端按 type 区分增量文本 / ReAct 轨迹 / 完成 / 失败:
 * <ul>
 *   <li>message     - 增量文本片段(delta 非空)</li>
 *   <li>tool_call   - ReAct 行动:LLM 决策调用工具(tool=工具名,detail=入参 JSON)</li>
 *   <li>tool_result - ReAct 观察:工具执行返回(tool=工具名,detail=结果摘要)</li>
 *   <li>complete    - 全量回复已落库,回传 assistantMessageId 供前端刷新</li>
 *   <li>error       - 流中途失败,error 携带降级文案</li>
 * </ul>
 */
public record ChatStreamChunk(String type, Long conversationId, String delta, Long assistantMessageId, String error,
        String tool, String detail) {

    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_TOOL_CALL = "tool_call";
    public static final String TYPE_TOOL_RESULT = "tool_result";
    public static final String TYPE_COMPLETE = "complete";
    public static final String TYPE_ERROR = "error";

    public static ChatStreamChunk message(Long conversationId, String delta) {
        return new ChatStreamChunk(TYPE_MESSAGE, conversationId, delta, null, null, null, null);
    }

    public static ChatStreamChunk toolCall(Long conversationId, String tool, String detail) {
        return new ChatStreamChunk(TYPE_TOOL_CALL, conversationId, null, null, null, tool, detail);
    }

    public static ChatStreamChunk toolResult(Long conversationId, String tool, String detail) {
        return new ChatStreamChunk(TYPE_TOOL_RESULT, conversationId, null, null, null, tool, detail);
    }

    public static ChatStreamChunk complete(Long conversationId, Long assistantMessageId) {
        return new ChatStreamChunk(TYPE_COMPLETE, conversationId, null, assistantMessageId, null, null, null);
    }

    public static ChatStreamChunk error(Long conversationId, String error) {
        return new ChatStreamChunk(TYPE_ERROR, conversationId, null, null, error, null, null);
    }
}
