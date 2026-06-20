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
public class NotificationConfigRequest {

    @NotNull(message = "appid 不能为空")
    private Long appid;

    @NotBlank(message = "收件人不能为空")
    private String target;

    @Builder.Default
    private String status = "enabled";
}
