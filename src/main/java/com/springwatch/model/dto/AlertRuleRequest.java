package com.springwatch.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRuleRequest {

    @NotNull(message = "appid 不能为空")
    private Long appid;

    @NotBlank(message = "规则名不能为空")
    private String ruleName;

    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    private String expression;

    private Double thresholdValue;

    @Builder.Default
    private Integer durationSeconds = 60;

    private String notifyChannels;

    @Builder.Default
    private String status = "enabled";

    @Builder.Default
    private String level = "warning";

    @Builder.Default
    private Integer times = 1;

    private String template;
}
