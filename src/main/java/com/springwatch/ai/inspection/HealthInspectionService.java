package com.springwatch.ai.inspection;

import com.springwatch.ai.agent.LlmInvoker;
import com.springwatch.ai.context.LogContextService;
import com.springwatch.ai.rag.VectorSearchService;
import com.springwatch.model.entity.ChatConversation;
import com.springwatch.model.entity.ChatMessage;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.ChatConversationRepository;
import com.springwatch.repository.ChatMessageRepository;
import com.springwatch.repository.MonitorAppRepository;
import com.springwatch.service.LogQueryService;
import com.springwatch.service.MetricQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P3 自动巡检报告 - 全应用健康体检。
 * 组合: 应用列表 + 关键指标摘要 + 错误日志指纹 + RAG 知识检索,
 * LLM 生成巡检报告,落库 system_push 并推送到会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthInspectionService {

    private static final List<String> KEY_METRICS = List.of(
            "jvm_memory_used_bytes",
            "jvm_gc_pause_seconds",
            "http_server_requests_seconds_count",
            "http_server_requests_seconds_max");

    private final MonitorAppRepository monitorAppRepository;
    private final MetricQueryService metricQueryService;
    private final LogQueryService logQueryService;
    private final LogContextService logContextService;
    private final VectorSearchService vectorSearchService;
    private final LlmInvoker llmInvoker;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;

    @Value("${ai.inspection.enabled:true}")
    private boolean enabled;

    @Value("${ai.inspection.prompt:}")
    private String inspectionPrompt;

    @Value("${ai.inspection.window-minutes:1440}")
    private long windowMinutes;

    @Scheduled(cron = "${ai.inspection.cron:0 0 8 * * MON}")
    public void runWeeklyInspection() {
        if (!enabled) {
            log.debug("[kxj: 自动巡检跳过 - enabled=false");
            return;
        }
        try {
            String report = inspectAll();
            pushToConversation(report);
            log.info("[kxj: 自动巡检完成 - reportLen={}]", report.length());
        } catch (Exception e) {
            log.warn("[kxj: 自动巡检失败 - error={}]", e.getMessage(), e);
        }
    }

    /** 生成全应用巡检报告(供手动调用) */
    public String inspectAll() {
        List<MonitorApp> apps = monitorAppRepository.findByStatusIgnoreCase("active");
        Instant from = Instant.now().minus(Duration.ofMinutes(Math.max(windowMinutes, 60)));
        Instant to = Instant.now();
        StringBuilder evidence = new StringBuilder();
        for (MonitorApp app : apps) {
            if ("self://infra".equals(app.getEndpoint())) {
                continue;
            }
            evidence.append(buildAppEvidence(app, from, to)).append('\n');
        }
        List<VectorSearchService.Hit> ragHits = vectorSearchService.search("应用巡检 健康检查 告警 常见故障", 3);
        StringBuilder ragText = new StringBuilder();
        if (!ragHits.isEmpty()) {
            ragText.append("历史知识片段:\n");
            for (VectorSearchService.Hit h : ragHits) {
                ragText.append("- [").append(h.source()).append("] ")
                        .append(truncate(h.content(), 200)).append('\n');
            }
        }
        String user = String.format(
                "请对以下应用巡检数据进行健康评估,输出结构化巡检报告:\n(1) 总体健康度\n(2) 按应用列出异常点与建议\n(3) 引用知识库给出处置建议\n\n%s\n%s",
                evidence, ragText);
        String content = llmInvoker.invoke("全应用巡检", inspectionPrompt, user,
                evidenceFallback(evidence.toString())).content();
        return content == null || content.isBlank() ? evidenceFallback(evidence.toString()) : content;
    }

    private String buildAppEvidence(MonitorApp app, Instant from, Instant to) {
        long appid = app.getAppid();
        StringBuilder sb = new StringBuilder();
        sb.append("## 应用: ").append(app.getAppName()).append(" (appid=").append(appid).append(")\n");
        for (String metric : KEY_METRICS) {
            Map<String, Object> r = metricQueryService.querySeries(appid, metric, from, to, "mean", "1h", Map.of());
            String summary = summarizeSeries(r);
            if (summary != null && !summary.isBlank()) {
                sb.append("- ").append(metric).append(": ").append(summary).append('\n');
            }
        }
        List<LogQueryService.PatternTop> tops = logQueryService.topFingerprints(appid, from, to, 5, "ERROR");
        if (!tops.isEmpty()) {
            sb.append("- ERROR 日志指纹 Top5: ");
            for (LogQueryService.PatternTop t : tops) {
                sb.append(t.count()).append("x ").append(truncate(t.pattern(), 80)).append("; ");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String summarizeSeries(Map<String, Object> r) {
        if (r == null) return null;
        Object series = r.get("series");
        if (!(series instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.getFirst();
        if (!(first instanceof Map<?, ?> m)) return null;
        Object points = m.get("points");
        if (!(points instanceof List<?> pl) || pl.isEmpty()) return null;
        double sum = 0;
        double max = Double.MIN_VALUE;
        double min = Double.MAX_VALUE;
        int n = 0;
        for (Object p : pl) {
            if (p instanceof Map<?, ?> pm) {
                Object v = pm.get("v");
                if (v instanceof Number num) {
                    double d = num.doubleValue();
                    sum += d;
                    n++;
                    max = Math.max(max, d);
                    min = Math.min(min, d);
                }
            }
        }
        if (n == 0) return null;
        return String.format("avg=%.2f min=%.2f max=%.2f (n=%d)", sum / n, min, max, n);
    }

    private String evidenceFallback(String evidence) {
        return "AI 巡检失败,以下为原始体检证据:\n\n" + evidence;
    }

    private void pushToConversation(String content) {
        ChatConversation conv = findOrCreateInspectionConversation();
        messageRepository.save(ChatMessage.builder()
                .conversation(conv)
                .role("system_push")
                .content(content)
                .build());
        log.info("[kxj: 巡检报告已落库 system_push - conversationId={}, len={}]", conv.getId(), content.length());
    }

    private ChatConversation findOrCreateInspectionConversation() {
        String title = "自动巡检";
        List<ChatConversation> existing = conversationRepository
                .findAllByOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(0, 200))
                .getContent()
                .stream()
                .filter(c -> title.equals(c.getTitle()))
                .toList();
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        return conversationRepository.save(ChatConversation.builder().title(title).build());
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}