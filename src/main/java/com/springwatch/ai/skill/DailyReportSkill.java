package com.springwatch.ai.skill;

import com.springwatch.ai.agent.AgentSkill;
import com.springwatch.ai.inspection.HealthInspectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 技能 - 全应用巡检报告。
 * 复用 HealthInspectionService(指标摘要 + 错误指纹 + RAG 检索 + LLM)。
 */
@Component
@RequiredArgsConstructor
public class DailyReportSkill implements AgentSkill {

    private final HealthInspectionService inspectionService;

    @Override
    public String name() {
        return "daily_report";
    }

    @Override
    public String description() {
        return "全应用巡检报告:关键指标+错误日志指纹+RAG历史知识,输出健康评级与处置建议";
    }

    @Override
    public String execute(Long appid) {
        return inspectionService.inspectAll();
    }
}
