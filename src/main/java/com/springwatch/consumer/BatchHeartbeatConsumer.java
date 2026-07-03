package com.springwatch.consumer;

import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.repository.MonitorAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchHeartbeatConsumer {

    private final ObjectMapper objectMapper;
    private final MonitorAppRepository monitorAppRepository;

    @KafkaListener(
            topics = "monitor-heartbeat",
            groupId = "spring-watch-heartbeat-writer",
            containerFactory = "batchFactory"
    )
    @Transactional
    public void onBatch(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Map<Long, HeartbeatSnapshot> latestPerAppid = new HashMap<>();
        int failed = 0;
        for (String message : messages) {
            try {
                HeartbeatEvent event = objectMapper.readValue(message, HeartbeatEvent.class);
                if (event.getAppid() == null) {
                    failed++;
                    continue;
                }
                latestPerAppid.merge(event.getAppid(),
                        new HeartbeatSnapshot(event.getIp(), event.getAgentVersion(), event.getTimestamp()),
                        (old, neu) -> event.getTimestamp().isAfter(old.timestamp) ? neu : old);
            } catch (Exception e) {
                failed++;
                log.warn("[kxj: BatchHeartbeatConsumer 反序列化失败 - error={}, payload={}",
                        e.getMessage(), message);
            }
        }
        if (latestPerAppid.isEmpty()) {
            log.info("[kxj: BatchHeartbeatConsumer 批次无可用心跳 - total={}, failed={}",
                    messages.size(), failed);
            return;
        }
        Set<Long> appids = latestPerAppid.keySet();
        List<MonitorApp> apps = monitorAppRepository.findAllByAppidIn(appids);
        if (apps.isEmpty()) {
            log.warn("[kxj: BatchHeartbeatConsumer 全部appid在DB中不存在 - appids={}", appids);
            return;
        }
        Instant now = Instant.now();
        Set<Long> matched = new HashSet<>();
        for (MonitorApp app : apps) {
            HeartbeatSnapshot snap = latestPerAppid.get(app.getAppid());
            if (snap == null) continue;
            matched.add(app.getAppid());
            app.setLastHeartbeat(now);
            app.setStatus("active");
        }
        monitorAppRepository.saveAll(apps);
        log.info("[kxj: BatchHeartbeatConsumer 更新心跳 - total={}, matched={}, failed={}, notInDb={}",
                messages.size(), matched.size(), failed, appids.size() - matched.size());
    }

    private record HeartbeatSnapshot(String ip, String agentVersion, Instant timestamp) {}
}
