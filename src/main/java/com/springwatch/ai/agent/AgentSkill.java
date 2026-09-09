package com.springwatch.ai.agent;

/**
 * 能力层 - 运维技能统一接口。
 * 实现类注册为 Spring Bean 后由 AgentRegistry 自动收集,可插拔扩展,
 * 替换原 SopEngine 中硬编码 switch 的技能分发。
 */
public interface AgentSkill {

    /** 技能名(唯一,SOP 定时任务 sopName 与技能调用工具均按此名寻址) */
    String name();

    /** 技能描述(展示给 LLM 与用户,用于技能发现) */
    String description();

    /**
     * 执行技能:采集证据 → LLM 分析 → 返回结构化报告文本。
     *
     * @param appid 目标应用,无目标应用可为 null
     */
    String execute(Long appid);
}
