package com.springwatch.model.event;

import lombok.*;

import java.time.Instant;


@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class HeartbeatEvent {

    private Long appid;
    private String ip;
    private String agentVersion;
    private Instant timestamp;

    public String toLogString() {
        return String.format("[HeartbeatEvent: appid=%s, ip=%s, agentVersion=%s, time=%s]",
                appid, ip, agentVersion, timestamp);
    }
}
