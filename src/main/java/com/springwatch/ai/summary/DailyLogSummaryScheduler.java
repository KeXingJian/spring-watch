package com.springwatch.ai.summary;

import com.springwatch.ai.context.LogContextService;
import com.springwatch.model.entity.ChatConversation;
import com.springwatch.model.entity.ChatMessage;
import com.springwatch.repository.ChatConversationRepository;
import com.springwatch.repository.ChatMessageRepository;
import com.springwatch.repository.MonitorAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * P1 日志智能摘要 - 每日错误摘要定时任务。
 * 每日 09:00 聚合昨日各应用 ERROR 日志指纹,生成结构化摘要,
 * 以 system_push 角色落库 chat_message(前端 AI 面板可查)。
 * LLM 失败不阻塞,直接落统计摘要。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyLogSummaryScheduler {

    private final LogContextService logContextService;
    private final MonitorAppRepository monitorAppRepository;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;

    @Value("${ai.log-summary.enabled:true}")
    private boolean enabled;

    @Value("${ai.log-summary.top-n:10}")
    private int topN;

    @Value("${ai.log-summary.window-hours:24}")
    private int windowHours;

    @Scheduled(cron = "${ai.log-summary.cron:0 0 9 * * *}")
    public void generateDailySummary() {
        if (!enabled) {
            log.debug("[kxj: 日志摘要任务跳过 - enabled=false");
            return;
        }
        try {
            Instant to = Instant.now();
            Instant from = to.minus(Duration.ofHours(Math.max(windowHours, 1)));
            List<Map<String, Object>> apps = monitorAppRepository.findByStatusIgnoreCase("active").stream()
                    .map(a -> {
                        Map<String, Object> m = new java.util.HashMap<>();
                        m.put("appid", a.getAppid());
                        m.put("appName", a.getAppName());
                        return m;
                    })
                    .toList();
            List<LogContextService.AppErrorSummary> summaries = logContextService.summarizeAll(apps, from, to, topN);
            String content = buildSummaryText(from, to, summaries);
            pushToConversation(content);
            log.info("[kxj: 每日日志摘要生成完成 - apps={}, errorApps={}, window={}h]",
                    apps.size(), summaries.size(), windowHours);
        } catch (Exception e) {
            log.warn("[kxj: 每日日志摘要任务失败 - error={}]", e.getMessage(), e);
        }
    }

    private String buildSummaryText(Instant from, Instant to,
                                    List<LogContextService.AppErrorSummary> summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 日志错误摘要\n\n");
        sb.append("**窗口**: ").append(from).append(" ~ ").append(to).append('\n');
        if (summaries.isEmpty()) {
            sb.append("窗口内无 ERROR 日志。\n");
            return sb.toString();
        }
        sb.append("**涉及应用**: ").append(summaries.size()).append(" 个\n\n");
        for (LogContextService.AppErrorSummary s : summaries) {
            sb.append("## ").append(s.appName()).append(" (appid=").append(s.appid()).append(")\n");
            sb.append("ERROR 总数: ").append(s.totalErrors()).append('\n');
            int rank = 0;
            for (Map.Entry<String, Long> e : s.fingerprintCounts().entrySet()) {
                if (++rank > 10) break;
                sb.append("- ").append(e.getValue()).append("x  `")
                        .append(truncate(e.getKey(), 120)).append("`\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void pushToConversation(String content) {
        ChatConversation conv = findOrCreateSystemConversation();
        messageRepository.save(ChatMessage.builder()
                .conversation(conv)
                .role("system_push")
                .content(content)
                .build());
        log.info("[kxj: 日志摘要已落库 system_push - conversationId={}, len={}]", conv.getId(), content.length());
    }

    private ChatConversation findOrCreateSystemConversation() {
        String title = "系统推送";
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
