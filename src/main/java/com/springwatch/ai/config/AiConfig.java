package com.springwatch.ai.config;

import com.springwatch.ai.tool.AlertQueryTool;
import com.springwatch.ai.tool.AppQueryTool;
import com.springwatch.ai.tool.LogQueryTool;
import com.springwatch.ai.tool.MetricQueryTool;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class AiConfig {

    private final AppQueryTool appQueryTool;
    private final MetricQueryTool metricQueryTool;
    private final LogQueryTool logQueryTool;
    private final AlertQueryTool alertQueryTool;

    @Bean
    public MethodToolCallbackProvider aiToolCallbackProvider() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(appQueryTool, metricQueryTool, logQueryTool, alertQueryTool)
                .build();
    }

    @Bean
    public ChatClient aiChatClient(ChatClient.Builder builder, MethodToolCallbackProvider toolProvider) {
        return builder
                .defaultTools(toolProvider)
                .build();
    }
}