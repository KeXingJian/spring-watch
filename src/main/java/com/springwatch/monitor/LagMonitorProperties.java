package com.springwatch.monitor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "spring-watch.kafka.lag-monitor")
public class LagMonitorProperties {

    private boolean enabled = true;
    private long pollIntervalSec = 30L;
}
