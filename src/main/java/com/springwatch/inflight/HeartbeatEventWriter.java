package com.springwatch.inflight;

import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.event.HeartbeatEvent;
import com.springwatch.repository.MonitorAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatEventWriter {

    private final MonitorAppRepository monitorAppRepository;

    public record WriteResult(int processed, int failed, int notInDb) {}

    @Transactional
    public WriteResult write(List<Object> events) {
        if (events == null || events.isEmpty()) return new WriteResult(0, 0, 0);
        Map<Long, HeartbeatSnapshot> latestPerAppid = new HashMap<>();
        int failed = 0;
        for (Object e : events) {
            if (!(e instanceof HeartbeatEvent event)) {
                failed++;
                continue;
            }
            if (event.getAppid() == null) {
                failed++;
                continue;
            }
            latestPerAppid.merge(event.getAppid(),
                new HeartbeatSnapshot(event.getIp(), event.getAgentVersion(), event.getTimestamp()),
                (old, neu) -> event.getTimestamp().isAfter(old.timestamp) ? neu : old);
        }
        if (latestPerAppid.isEmpty()) {
            return new WriteResult(events.size(), failed, 0);
        }
        Set<Long> appids = latestPerAppid.keySet();
        List<MonitorApp> apps = monitorAppRepository.findAllByAppidIn(appids);
        if (apps.isEmpty()) {
            return new WriteResult(events.size(), failed, appids.size());
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
        return new WriteResult(events.size(), failed, appids.size() - matched.size());
    }

    private record HeartbeatSnapshot(String ip, String agentVersion, Instant timestamp) {}
}
