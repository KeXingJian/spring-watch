package com.springwatch.ai.config;

import com.springwatch.ai.agent.SkillInvokeTool;
import com.springwatch.ai.agent.ToolTraceCallback;
import com.springwatch.ai.tool.AlertQueryTool;
import com.springwatch.ai.tool.AppQueryTool;
import com.springwatch.ai.tool.LogQueryTool;
import com.springwatch.ai.tool.MetricQueryTool;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
@RequiredArgsConstructor
public class AiConfig {

    private final AppQueryTool appQueryTool;
    private final MetricQueryTool metricQueryTool;
    private final LogQueryTool logQueryTool;
    private final AlertQueryTool alertQueryTool;
    private final SkillInvokeTool skillInvokeTool;

    /**
     * 工具回调统一包一层轨迹装饰器,使 ReAct 循环中的工具调用/结果可被对话 SSE 观测。
     */
    @Bean
    public ToolCallbackProvider aiToolCallbackProvider() {
        ToolCallback[] raw = MethodToolCallbackProvider.builder()
                .toolObjects(appQueryTool, metricQueryTool, logQueryTool, alertQueryTool, skillInvokeTool)
                .build()
                .getToolCallbacks();
        ToolCallback[] traced = Arrays.stream(raw)
                .map(ToolTraceCallback::new)
                .toArray(ToolCallback[]::new);
        return ToolCallbackProvider.from(traced);
    }

    @Bean
    public ChatClient aiChatClient(ChatClient.Builder builder, ToolCallbackProvider aiToolCallbackProvider) {
        return builder
                .defaultTools(aiToolCallbackProvider)
                .build();
    }
}
