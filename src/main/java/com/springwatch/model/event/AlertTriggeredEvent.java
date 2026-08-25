package com.springwatch.model.event;

import com.springwatch.model.entity.AlertRule;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

/**
 * 告警 FIRING 事件 - AlertEngine 状态机升级到 FIRING 后发布(事务提交后)
 * 由 ai 模块 AlertDiagnosisTrigger 订阅,异步触发智能诊断,不阻塞告警通知主链路
 */
@Getter
@RequiredArgsConstructor
public class AlertTriggeredEvent {

    private final AlertRule rule;
    private final Long appid;
    private final String metric;
    private final Double value;
    private final Long historyId;
    private final Instant triggeredAt;
}