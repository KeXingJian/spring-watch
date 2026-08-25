package com.springwatch.alerter;

import com.springwatch.model.entity.MonitorApp;
import com.springwatch.service.MonitorAppService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P1 告警收敛 - 风暴摘要周期推送。
 * 周期性扫描当前活跃收敛组,对达到风暴阈值(被抑制数 >= minStormCount)的组
 * 推送一次汇总通知(邮件),避免风暴期逐条刷屏后又完全无声。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertStormSummaryScheduler {

    private final AlertAggregationService aggregationService;
    private final AlertNotifier notifier;
    private final MonitorAppService monitorAppService;
    private final MeterRegistry meterRegistry;

    @Value("${spring-watch.alert.aggregation.enabled:true}")
    private boolean aggregationEnabled;

    @Value("${spring-watch.alert.aggregation.summary-push-enabled:true}")
    private boolean summaryPushEnabled;

    private Counter summaryPushedCounter;

    @PostConstruct
    void init() {
        this.summaryPushedCounter = Counter.builder("spring.watch.alerter.storm_summary.pushed")
                .description("风暴摘要推送次数")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${spring-watch.alert.aggregation.summary-interval-ms:60000}")
    public void pushStormSummaries() {
        if (!aggregationEnabled || !summaryPushEnabled) {
            return;
        }
        try {
            List<AlertAggregationService.StormGroup> storms = aggregationService.activeStormGroups();
            if (storms.isEmpty()) {
                return;
            }
            for (AlertAggregationService.StormGroup s : storms) {
                String appName = resolveAppName(s.similarityKey());
                notifier.notifyStormSummary(parseAppid(s.similarityKey()), appName,
                        s.groupId(), s.groupCount(), s.suppressedCount(), s.firstAt());
                summaryPushedCounter.increment();
                log.info("[kxj: 风暴摘要推送 - groupId={}, appid={}, count={}, suppressed={}]",
                        s.groupId(), parseAppid(s.similarityKey()), s.groupCount(), s.suppressedCount());
            }
        } catch (Exception e) {
            log.warn("[kxj: 风暴摘要推送失败 - error={}]", e.getMessage(), e);
        }
    }

    private long parseAppid(String similarityKey) {
        try {
            int idx = similarityKey.indexOf('|');
            return idx > 0 ? Long.parseLong(similarityKey.substring(0, idx)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private String resolveAppName(String similarityKey) {
        long appid = parseAppid(similarityKey);
        if (appid < 0) {
            return "appid=" + appid;
        }
        return monitorAppService.findByAppid(appid)
                .map(MonitorApp::getAppName)
                .orElse("appid=" + appid);
    }
}
