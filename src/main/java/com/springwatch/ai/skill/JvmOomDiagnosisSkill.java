package com.springwatch.ai.skill;

import com.springwatch.ai.agent.AgentSkill;
import com.springwatch.ai.agent.LlmInvoker;
import com.springwatch.ai.context.LogContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 技能 - JVM OOM 诊断。
 * 聚合过去 24h 内存趋势相关 ERROR 指纹,LLM 分析,失败时降级证据文本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JvmOomDiagnosisSkill implements AgentSkill {

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是 spring-watch 监控平台的运维技能专家,请基于证据输出结构化诊断与建议,不编造。";

    private final LogContextService logContextService;
    private final LlmInvoker llmInvoker;

    @Value("${ai.sop.prompt:}")
    private String sopPrompt;

    @Override
    public String name() {
        return "jvm_oom_diagnosis";
    }

    @Override
    public String description() {
        return "JVM OOM 诊断:按 appid 聚合 24h 内存/错误日志证据,LLM 分析根因";
    }

    @Override
    public String execute(Long appid) {
        if (appid == null) {
            return "JVM OOM 诊断需要指定 appid。";
        }
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(24));
        Map<String, Object> app = new java.util.HashMap<>();
        app.put("appid", appid);
        List<LogContextService.AppErrorSummary> summaries =
                logContextService.summarizeAll(List.of(app), from, to, 10);
        String evidence = summaries.isEmpty() ? "无 ERROR 日志" : buildEvidenceText(summaries);
        return llmInvoker.invoke("JVM OOM 诊断", systemPrompt(), "appid=" + appid + "\n" + evidence, evidence)
                .content();
    }

    private String systemPrompt() {
        return sopPrompt == null || sopPrompt.isBlank() ? DEFAULT_SYSTEM_PROMPT : sopPrompt;
    }

    private String buildEvidenceText(List<LogContextService.AppErrorSummary> summaries) {
        StringBuilder sb = new StringBuilder();
        for (LogContextService.AppErrorSummary s : summaries) {
            sb.append("应用 ").append(s.appName()).append(" ERROR 总数=").append(s.totalErrors()).append('\n');
            int i = 0;
            for (Map.Entry<String, Long> e : s.fingerprintCounts().entrySet()) {
                if (++i > 5) break;
                sb.append("- ").append(e.getValue()).append("x ").append(truncate(e.getKey(), 120)).append('\n');
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
