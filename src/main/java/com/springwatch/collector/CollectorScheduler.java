package com.springwatch.collector;

import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.MonitorAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectorScheduler {

    private final MonitorAppRepository monitorAppRepository;
    private final HttpProbe httpProbe;

    @Scheduled(fixedDelayString = "${spring-watch.collector.interval:15000}")
    public void scheduleProbe() {
        List<MonitorApp> activeApps = monitorAppRepository.findByStatus("active");
        if (activeApps.isEmpty()) {
            log.debug("[spring-watch: CollectorScheduler 探活 - 无active应用, 跳过本轮]");
            return;
        }
        log.info("[spring-watch: CollectorScheduler 探活开始 - 应用数={}]", activeApps.size());

        for (MonitorApp app : activeApps) {
            try {
                httpProbe.probe(app);
            } catch (Exception e) {
                log.warn("[spring-watch: 探活异常 - app={}, error={}]", app.getAppName(), e.getMessage());
            }
        }
    }
}
