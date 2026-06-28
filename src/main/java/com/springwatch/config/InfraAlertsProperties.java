package com.springwatch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "spring-watch.infra-alerts")
public class InfraAlertsProperties {

    private boolean enabled = true;
    private long appid = 0L;
    private String appName = "infra-self";
    private long pollIntervalMs = 60000L;
    private List<Rule> rules = new ArrayList<>();

    @Data
    public static class Rule {
        private String name;
        private String component;
        private String metric;
        private Map<String, String> tag;
        private double threshold;
        private String op = ">";
        private String expression;
        private String level = "warning";
        private long cooldownSeconds = 300L;
    }
}
