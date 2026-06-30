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

}
