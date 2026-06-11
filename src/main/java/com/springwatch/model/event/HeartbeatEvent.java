package com.springwatch.model.event;

import lombok.*;

import java.time.Instant;


@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
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