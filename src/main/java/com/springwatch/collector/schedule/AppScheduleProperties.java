package com.springwatch.collector.schedule;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "spring-watch.collector")
public class AppScheduleProperties {

    private int poolSize = 32;

    private int globalConcurrent = 50;

    private int perHostConcurrent = 4;

    private int jitterPercent = 10;

    private int firstDelaySpreadMultiplier = 2;

    private Retry retry = new Retry();

    @Data
    public static class Retry {
        private int drainerCount = 2;
        private int maxAttempts = 5;
        private int maxQueueSize = 1000;
    }
}
