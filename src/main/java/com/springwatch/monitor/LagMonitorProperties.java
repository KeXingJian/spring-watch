package com.springwatch.monitor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "spring-watch.kafka.lag-monitor")
public class LagMonitorProperties {

    private boolean enabled = true;
    private String groupId = "spring-watch";
    private String[] topics = {"monitor-metrics", "monitor-logs", "monitor-heartbeat"};
    private long pollIntervalSec = 30L;
    private Map<String, String> groups = new HashMap<>();
}
