package com.springwatch.model.event;

import lombok.*;

import java.time.Instant;


@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class LogEvent {

    private String appName;
    private String level;
    private String logger;
    private String threadName;
    private String message;
    private String throwable;
    private String traceId;
    private Instant timestamp;


}