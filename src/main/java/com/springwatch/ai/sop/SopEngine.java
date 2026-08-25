package com.springwatch.ai.sop;

import com.springwatch.ai.context.LogContextService;
import com.springwatch.ai.inspection.HealthInspectionService;
import com.springwatch.model.entity.ChatConversation;
import com.springwatch.model.entity.ChatMessage;
import com.springwatch.model.entity.SopSchedule;
import com.springwatch.repository.ChatConversationRepository;
import com.springwatch.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 远期 - SOP 技能引擎。
 * 支持技能:
 *   daily_report          每日巡检报告(复用 HealthInspectionService)
 *   error_surge_diagnosis 错误率突增诊断(按 appid 聚合错误指纹,LLM 分析)
 *   jvm_oom_diagnosis     JVM OOM 诊断(内存趋势 + 错误日志,LLM 分析)
 * 结果以 system_push 落库到"SOP技能"会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SopEngine {

    private final HealthInspectionService inspectionService;
    private final LogContextService logContextService;
    private final ChatClient aiChatClient;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final com.springwatch.repository.SopScheduleRepository scheduleRepository;

    @Value("${ai.sop.prompt:}")
    private String sopPrompt;

    @Scheduled(fixedDelayString = "${ai.sop.scan-interval-ms:60000}")
    public void scanDueTasks() {
        try {
            List<SopSchedule> tasks = scheduleRepository.findByEnabledTrue();
            Instant now = Instant.now();
            for (SopSchedule t : tasks) {
                if (t.getNextRunTime() != null && t.getNextRunTime().isAfter(now)) {
                    continue;
                }
                log.info("[kxj: SOP 任务到点执行 - id={}, sop={}, appid={}]", t.getId(), t.getSopName(), t.getAppid());
                try {
                    run(t.getSopName(), t.getAppid());
                    t.setLastRunTime(now);
                    t.setNextRunTime(nextRun(t.getCronExpression(), now));
                    scheduleRepository.save(t);
                } catch (Exception e) {
                    log.warn("[kxj: SOP 任务执行失败 - id={}, sop={}, error={}]",
                            t.getId(), t.getSopName(), e.getMessage());
                    t.setNextRunTime(now.plus(Duration.ofMinutes(5)));
                    scheduleRepository.save(t);
                }
            }
        } catch (Exception e) {
            log.warn("[kxj: SOP 任务扫描失败 - error={}]", e.getMessage(), e);
        }
    }

    private Instant nextRun(String cron, Instant from) {
        try {
            return org.springframework.scheduling.support.CronExpression.parse(cron).next(from);
        } catch (Exception e) {
            return from.plus(Duration.ofHours(24));
        }
    }

    public void run(String sopName, Long appid) {
        String content = switch (sopName == null ? "" : sopName) {
            case "daily_report" -> inspectionService.inspectAll();
            case "error_surge_diagnosis" -> errorSurgeDiagnosis(appid);
            case "jvm_oom_diagnosis" -> jvmOomDiagnosis(appid);
            default -> "未知技能: " + sopName;
        };
        pushToConversation(sopName, content);
    }

    private String errorSurgeDiagnosis(Long appid) {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(24));
        Map<String, Object> app = new java.util.HashMap<>();
        app.put("appid", appid);
        List<LogContextService.AppErrorSummary> summaries =
                logContextService.summarizeAll(List.of(app), from, to, 10);
        if (summaries.isEmpty()) {
            return "过去 24h 该应用无 ERROR 日志,无需诊断。";
        }
        String evidence = buildEvidenceText(summaries);
        return callLlm("错误率突增诊断", "appid=" + appid + "\n" + evidence);
    }

    private String jvmOomDiagnosis(Long appid) {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(24));
        Map<String, Object> app = new java.util.HashMap<>();
        app.put("appid", appid);
        List<LogContextService.AppErrorSummary> summaries =
                logContextService.summarizeAll(List.of(app), from, to, 10);
        String evidence = summaries.isEmpty() ? "无 ERROR 日志" : buildEvidenceText(summaries);
        return callLlm("JVM OOM 诊断", "appid=" + appid + "\n" + evidence);
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

    private String callLlm(String task, String evidence) {
        try {
            String content = aiChatClient.prompt()
                    .system(sopPrompt == null || sopPrompt.isBlank()
                            ? "你是 spring-watch 监控平台的运维技能专家,请基于证据输出结构化诊断与建议,不编造。"
                            : sopPrompt)
                    .user("任务: " + task + "\n\n证据:\n" + evidence)
                    .call()
                    .content();
            return content == null || content.isBlank() ? evidence : content;
        } catch (Exception e) {
            log.warn("[kxj: SOP LLM 调用失败,降级证据 - error={}]", e.getMessage());
            return evidence;
        }
    }

    private void pushToConversation(String sopName, String content) {
        ChatConversation conv = findOrCreateSopConversation();
        messageRepository.save(ChatMessage.builder()
                .conversation(conv)
                .role("system_push")
                .content("[SOP:" + sopName + "] " + Instant.now() + "\n\n" + content)
                .build());
    }

    private ChatConversation findOrCreateSopConversation() {
        String title = "SOP技能";
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