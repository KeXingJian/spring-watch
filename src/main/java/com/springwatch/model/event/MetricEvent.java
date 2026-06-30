package com.springwatch.model.event;

import lombok.*;

import java.time.Instant;
import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class MetricEvent {

    private Long appid;
    private String metricName;
    private String method;
    private Double value;
    private Long count;
    private Instant timestamp;
    private Map<String, String> tags;


}
