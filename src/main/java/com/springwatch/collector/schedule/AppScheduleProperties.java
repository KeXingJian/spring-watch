package com.springwatch.collector.schedule;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "spring-watch.collector")
public class AppScheduleProperties {

    private int poolSize = 32;

    private int jitterPercent = 10;

    private int firstDelaySpreadMultiplier = 2;

    private int maxAppCount = 1024;

    private Retry retry = new Retry();

    private Http http = new Http();

    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    private Schedule schedule = new Schedule();

    @Data
    public static class Retry {
        private int drainerCount = 2;
        private int maxAttempts = 5;
        private int maxQueueSize = 1000;
    }

    @Data
    public static class Http {
        private int defaultReadTimeoutMs = 2500;
        private int minReadTimeoutMs = 300;
        private int maxReadTimeoutMs = 2500;
        private int connectTimeoutMs = 3000;
    }

    @Data
    public static class CircuitBreaker {
        private int windowSize = 20;
        private long slowThresholdMs = 1500L;
        private int failureRatePercent = 50;
        private int slowRatePercent = 60;
        private int consecutiveTimeoutsToOpen = 3;
        private long initialCoolDownMs = 10_000L;
        private long maxCoolDownMs = 60_000L;
    }

    @Data
    public static class Schedule {
        private double slowFactorMin = 0.5;
        private double slowFactorMax = 5.0;
        private double degradeMultiplier = 1.5;
        private double recoverMultiplier = 0.9;
        private long p95DegradeMs = 3000L;
        private long p95RecoverMs = 1000L;
        private int errorRateDegradePercent = 10;
        private int errorRateRecoverPercent = 1;
        private long healthTickMs = 5000L;
    }
}
