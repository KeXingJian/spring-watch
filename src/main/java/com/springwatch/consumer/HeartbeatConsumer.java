package com.springwatch.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.repository.MonitorAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatConsumer {

    private final ObjectMapper objectMapper;
    private final MonitorAppRepository monitorAppRepository;

    @KafkaListener(topics = "monitor-heartbeat", groupId = "spring-watch-heartbeat-consumer")
    public void onHeartbeat(String message) {
        try {
            HeartbeatEvent event = objectMapper.readValue(message, HeartbeatEvent.class);
            log.info("[spring-watch: HeartbeatConsumer 收到心跳 - app={}, ip={}, agentVersion={}]",
                    event.getAppName(), event.getIp(), event.getAgentVersion());

            monitorAppRepository.findByAppName(event.getAppName()).ifPresent(app -> {
                app.setLastHeartbeat(Instant.now());
                app.setStatus("active");
                monitorAppRepository.save(app);
                log.debug("[spring-watch: 心跳更新成功 - app={}]", event.getAppName());
            });
        } catch (Exception e) {
            log.error("[spring-watch: HeartbeatConsumer 处理失败 - error={}]", e.getMessage(), e);
        }
    }
}