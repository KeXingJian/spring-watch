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
    private final PrometheusScraper prometheusScraper;
    private final HttpProbe httpProbe;

    @Scheduled(fixedDelayString = "${spring-watch.collector.interval:15000}")
    public void scheduleCollection() {
        List<MonitorApp> activeApps = monitorAppRepository.findByStatus("active");
        if (activeApps.isEmpty()) {
            log.debug("[spring-watch: CollectorScheduler 定时采集 - 无active应用, 跳过本轮]");
            return;
        }
        log.info("[spring-watch: CollectorScheduler 定时采集开始 - 应用数={}]", activeApps.size());

        for (MonitorApp app : activeApps) {
            String mode = app.getCollectMode();
            PrometheusScraper.MonitorAppScraper scraper = new PrometheusScraper.MonitorAppScraper(
                    app.getAppName(), app.getEndpoint(), app.getMetricsPort());

            try {
                switch (mode) {
                    case "prometheus" -> prometheusScraper.scrape(scraper);
                    case "http_probe" -> httpProbe.probe(app);
                    default -> {
                        prometheusScraper.scrape(scraper);
                        httpProbe.probe(app);
                    }
                }
            } catch (Exception e) {
                log.warn("[spring-watch: 采集异常 - app={}, mode={}, error={}]",
                        app.getAppName(), mode, e.getMessage());
            }
        }
    }
}