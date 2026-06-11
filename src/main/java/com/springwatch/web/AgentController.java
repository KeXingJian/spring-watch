package com.springwatch.web;

import com.springwatch.collector.KafkaProducerBridge;
import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.model.event.LogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final KafkaProducerBridge kafkaProducerBridge;

    @PostMapping("/log")
    public ApiResponse<Void> receiveLog(@RequestBody LogEvent event) {
        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now());
        }
        log.debug("[spring-watch: Agent推送日志 - app={}, level={}, logger={}]",
                event.getAppName(), event.getLevel(), event.getLogger());
        kafkaProducerBridge.sendLog(event);
        return ApiResponse.ok(null);
    }

    @PostMapping("/heartbeat")
    public ApiResponse<Void> receiveHeartbeat(@RequestBody HeartbeatEvent event) {
        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now());
        }
        log.debug("[spring-watch: Agent推送心跳 - app={}, ip={}, agentVersion={}]",
                event.getAppName(), event.getIp(), event.getAgentVersion());
        kafkaProducerBridge.sendHeartbeat(event);
        return ApiResponse.ok(null);
    }
}
