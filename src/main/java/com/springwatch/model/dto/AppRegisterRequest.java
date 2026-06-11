package com.springwatch.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppRegisterRequest {

    @NotBlank(message = "应用名称不能为空")
    private String appName;

    private String endpoint;

    @Builder.Default
    private Integer metricsPort = 9464;

    @Builder.Default
    private String appType = "springboot";

    @Builder.Default
    @Positive
    private Integer scrapeInterval = 15;

    private String labels;
}