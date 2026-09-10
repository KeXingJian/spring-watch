package com.springwatch.ai.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 能力层 - 工具调用轨迹装饰器。
 * 包装 @Tool 生成的 ToolCallback,在 ReAct 工具调用循环中把"行动/观察"实时投递给
 * ToolTraceEmitter(经 ToolContext 关联到发起对话的请求),无发射器时静默降级。
 */
@Slf4j
@RequiredArgsConstructor
public class ToolTraceCallback implements ToolCallback {

    private final ToolCallback delegate;

    @Override
    public @NonNull ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public @NonNull ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public @NonNull String call(@NonNull String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public @NonNull String call(@NonNull String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        ToolTraceEmitter emitter = resolve(toolContext);
        emitter.onToolCall(toolName, toolInput);
        long start = System.currentTimeMillis();
        String result;
        try {
            result = delegate.call(toolInput, toolContext);
        } catch (Exception e) {
            log.warn("[kxj: ReAct 工具执行异常 - tool={}, error={}]", toolName, e.getMessage());
            emitter.onToolResult(toolName, "工具执行异常: " + e.getMessage());
            throw e;
        }
        log.info("[kxj: ReAct 工具执行完成 - tool={}, costMs={}]", toolName, System.currentTimeMillis() - start);
        emitter.onToolResult(toolName, result);
        return result;
    }

    private ToolTraceEmitter resolve(ToolContext toolContext) {
        if (toolContext == null) {
            return ToolTraceEmitter.NOOP;
        }
        Object emitter = toolContext.getContext().get(ToolTraceEmitter.KEY);
        return emitter instanceof ToolTraceEmitter e ? e : ToolTraceEmitter.NOOP;
    }
}
