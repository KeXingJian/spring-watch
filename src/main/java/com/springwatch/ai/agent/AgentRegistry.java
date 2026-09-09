package com.springwatch.ai.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 编排层 - 技能注册表。
 * 自动收集所有 AgentSkill Bean,按技能名寻址执行,替代硬编码 switch。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRegistry {

    private final List<AgentSkill> skills;

    public List<AgentSkill> all() {
        return skills.stream()
                .sorted(Comparator.comparing(AgentSkill::name))
                .toList();
    }

    public Optional<AgentSkill> find(String name) {
        return skills.stream().filter(s -> s.name().equalsIgnoreCase(name)).findFirst();
    }

    /** 执行技能,未知技能返回可用技能清单提示 */
    public String run(String name, Long appid) {
        return find(name)
                .map(s -> {
                    log.info("[kxj: 技能执行 - skill={}, appid={}]", s.name(), appid);
                    return s.execute(appid);
                })
                .orElseGet(() -> "未知技能: " + name + "。可用技能: "
                        + all().stream().map(AgentSkill::name).collect(Collectors.joining(", ")));
    }

    /** 技能清单文本(注入 chat 系统提示词,供 LLM 技能发现) */
    public String skillListText() {
        return all().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
    }
}
