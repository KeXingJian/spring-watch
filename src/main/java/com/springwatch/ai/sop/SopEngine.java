package com.springwatch.ai.sop;

import com.springwatch.ai.agent.AgentRegistry;
import com.springwatch.model.entity.ChatConversation;
import com.springwatch.model.entity.ChatMessage;
import com.springwatch.model.entity.SopSchedule;
import com.springwatch.repository.ChatConversationRepository;
import com.springwatch.repository.ChatMessageRepository;
import com.springwatch.repository.SopScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * SOP 技能调度器 - 仅负责定时扫描与结果推送,技能本身已拆为 AgentSkill(可插拔注册)。
 * 技能执行统一委托 AgentRegistry,不再维护硬编码 switch。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SopEngine {

    private final AgentRegistry agentRegistry;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final SopScheduleRepository scheduleRepository;

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

    /** 执行技能(由注册表寻址)并推送到 SOP 会话 */
    public void run(String sopName, Long appid) {
        String content = agentRegistry.run(sopName, appid);
        pushToConversation(sopName, content);
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
}
