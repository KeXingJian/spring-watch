package com.springwatch.model.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricEvent {

    private String appName;
    private String metricName;
    private String method;
    private Double value;
    private Long count;
    private Instant timestamp;
    private Map<String, String> tags;

    public String toLogString() {
        return String.format("[MetricEvent: app=%s, metric=%s, method=%s, value=%.2f, time=%s]",
                appName, metricName, method, value, timestamp);
    }
}