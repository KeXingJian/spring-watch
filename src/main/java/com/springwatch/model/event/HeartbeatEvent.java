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
public class HeartbeatEvent {

    private String appName;
    private String ip;
    private String agentVersion;
    private Instant timestamp;

    public String toLogString() {
        return String.format("[HeartbeatEvent: app=%s, ip=%s, agentVersion=%s, time=%s]",
                appName, ip, agentVersion, timestamp);
    }
}