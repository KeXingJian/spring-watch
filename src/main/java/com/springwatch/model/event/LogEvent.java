package com.springwatch.model.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogEvent {

    private String appName;
    private String level;
    private String logger;
    private String threadName;
    private String message;
    private String throwable;
    private String traceId;
    private Instant timestamp;

    public String toLogString() {
        return String.format("[LogEvent: app=%s, level=%s, logger=%s, time=%s]",
                appName, level, logger, timestamp);
    }
}