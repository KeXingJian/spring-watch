package com.springwatch.ai.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 能力层 - 技能调用工具。
 * 将 AgentRegistry 中的技能暴露为 @Tool,使 chat Agent 在工具调用循环中
 * 可自主选择并执行运维技能(诊断/巡检),打通"对话 → 技能 → 感知层"链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillInvokeTool {

    private final AgentRegistry agentRegistry;

    @Tool(description = "执行平台内置运维技能。常用技能:daily_report(全应用巡检报告)、error_surge_diagnosis(错误突增诊断)、jvm_oom_diagnosis(JVM OOM 诊断)。技能会自行采集指标/日志证据并生成结构化报告")
    public String runSkill(
            @ToolParam(description = "技能名") String skillName,
            @ToolParam(description = "目标应用 appid,无目标应用可为空") Long appid) {
        log.info("[kxj: AI工具 技能调用 - skill={}, appid={}]", skillName, appid);
        return agentRegistry.run(skillName, appid);
    }
}
