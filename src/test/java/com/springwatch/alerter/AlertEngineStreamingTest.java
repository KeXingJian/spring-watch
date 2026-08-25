package com.springwatch.alerter;

import com.springwatch.analysis.LogAnomalyDetector;
import com.springwatch.config.JexlConfig;
import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertNotificationConfig;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.event.LogEvent;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.AlertHistoryRepository;
import com.springwatch.repository.AlertNotificationConfigRepository;
import com.springwatch.repository.AlertRuleRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.commons.jexl3.JexlEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * kxj: 告警引擎流式单测
 * 不停向 AsyncAlertExecutor(真实生产异步路径)灌 MetricEvent/LogEvent,观察状态机与历史落库行为。
 * 覆盖场景:
 *  1. metric 规则: 持续超阈值 → PENDING → FIRING(历史落库), 恢复后 resolve + 清状态
 *  2. log_keyword 规则: 触发后普通日志不再闪断(grace 窗口), 持续无命中才恢复
 *  3. JEXL 多指标规则: 不相关 metric 事件不误恢复(lastMetric 守卫)
 *  4. 扫描器兜底恢复: 数据停止上报的 stale FIRING 被强制关闭
 *  5. fire 失败回退: DB 异常时状态回退 PENDING, 事件流继续后自动重试成功
 */
class AlertEngineStreamingTest {

    private static final long APPID = 1L;

    private final AtomicInteger saveCalls = new AtomicInteger();
    private AlertEngine engine;
    private AlertStateStore stateStore;
    private AlertRuleCache ruleCache;
    private AlertHistoryRepository historyRepository;
    private AsyncAlertExecutor asyncExecutor;
    private JavaMailSender mailSender;
    private AlertRuleRepository ruleRepository;

    private final List<AlertRule> activeRules = new ArrayList<>();
    private final AtomicReference<AlertHistory> lastHistory = new AtomicReference<>();
    private final AtomicBoolean failFirstSave = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        JexlEngine jexl = new JexlConfig().jexlEngine();
        JexlExprEvaluator jexlEvaluator = new JexlExprEvaluator(jexl, meters);
        ReflectionTestUtils.setField(jexlEvaluator, "cacheSize", 256);
        ReflectionTestUtils.setField(jexlEvaluator, "cacheExpireMinutes", 60L);
        jexlEvaluator.initMetrics();
        AlertEvaluator evaluator = new AlertEvaluator(jexlEvaluator);

        stateStore = new AlertStateStore(meters);
        ReflectionTestUtils.setField(stateStore, "ttlHours", 24L);
        ReflectionTestUtils.setField(stateStore, "maxEntries", 10000L);
        ReflectionTestUtils.setField(stateStore, "scanMaxEntries", 200L);
        stateStore.init();

        ruleRepository = mock(AlertRuleRepository.class);
        when(ruleRepository.findByStatus("enabled")).thenReturn(activeRules);
        ruleCache = new AlertRuleCache(ruleRepository);
        ReflectionTestUtils.setField(ruleCache, "refreshIntervalMs", 30000L);
        ReflectionTestUtils.setField(ruleCache, "maxAppids", 1000L);
        ruleCache.init();

        historyRepository = mock(AlertHistoryRepository.class);
        when(historyRepository.save(any(AlertHistory.class))).thenAnswer(inv -> {
            AlertHistory h = inv.getArgument(0);
            if (failFirstSave.get() && saveCalls.incrementAndGet() == 1) {
                throw new RuntimeException("db down");
            }
            saveCalls.incrementAndGet();
            lastHistory.set(h);
            return h;
        });
        when(historyRepository.findByAppAppidAndRuleIdAndResolvedAtIsNullOrderByCreatedAtDesc(anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    AlertHistory h = lastHistory.get();
                    return h == null || h.getResolvedAt() != null ? List.of() : List.of(h);
                });

        AlertNotificationConfigRepository notifyConfigRepository = mock(AlertNotificationConfigRepository.class);
        when(notifyConfigRepository.findByAppidAndStatus(anyLong(), any()))
                .thenReturn(List.of(AlertNotificationConfig.builder().appid(APPID).target("test@example.com").build()));
        mailSender = mock(JavaMailSender.class);
        AsyncMailExecutor mailExecutor = new AsyncMailExecutor();
        ReflectionTestUtils.setField(mailExecutor, "poolSize", 2);
        ReflectionTestUtils.setField(mailExecutor, "shutdownSeconds", 1);
        AlertNotifier notifier = new AlertNotifier(mailSender, new ObjectMapper(), notifyConfigRepository, mailExecutor);
        ReflectionTestUtils.setField(notifier, "alertEnabled", true);
        notifier.init();

        LogAnomalyDetector anomalyDetector = new LogAnomalyDetector(meters);
        ReflectionTestUtils.setField(anomalyDetector, "rateTtlSeconds", 60L);
        ReflectionTestUtils.setField(anomalyDetector, "patternTtlHours", 168L);
        ReflectionTestUtils.setField(anomalyDetector, "minBaseRate", 0.01);
        ReflectionTestUtils.setField(anomalyDetector, "maxAppids", 200L);
        ReflectionTestUtils.setField(anomalyDetector, "maxPatternsPerAppid", 1000);
        anomalyDetector.init();

        AlertAggregationService aggregationService = new AlertAggregationService();
        ReflectionTestUtils.setField(aggregationService, "aggregationEnabled", true);
        ReflectionTestUtils.setField(aggregationService, "silenceMinutes", 5L);
        ReflectionTestUtils.setField(aggregationService, "minStormCount", 3);
        aggregationService.init();

        engine = new AlertEngine(evaluator, stateStore, ruleCache, anomalyDetector,
                new AlertLifecycleService(stateStore, notifier, historyRepository,
                        mock(org.springframework.context.ApplicationEventPublisher.class),
                        aggregationService));
        ReflectionTestUtils.setField(engine, "alertEnabled", true);
        ReflectionTestUtils.setField(engine, "logRecoverGraceSeconds", 5L);

        asyncExecutor = new AsyncAlertExecutor(engine);
        ReflectionTestUtils.setField(asyncExecutor, "poolSize", 4);
        asyncExecutor.init();
    }

    @AfterEach
    void tearDown() {
        asyncExecutor.shutdown();
        saveCalls.set(0);
        failFirstSave.set(false);
        lastHistory.set(null);
        activeRules.clear();
    }

    // ==================== 场景 1: metric 规则流式触发 + 恢复 ====================
    @Test
    void metricRule_streaming_breachThenRecover() {
        MonitorApp app = app();
        AlertRule rule = AlertRule.builder()
                .id(100L).app(app).ruleName("cpu-high").ruleType("metric")
                .expression("cpu_usage > 80").durationSeconds(1).times(1)
                .status("enabled").level("warning").build();
        activeRules.add(rule);
        ruleCache.refresh();

        Streamer breach = new Streamer(10, () -> asyncExecutor.submit(metricEvent(APPID, "cpu_usage", 90.0)));
        try {
            awaitTrue("FIRING 状态", () -> stateStore.getState(100L, APPID) == AlertState.FIRING, 5000);
            // fire() 异步落库, CAS FIRING 先于历史保存,需等历史写完成再断言
            awaitTrue("告警历史已落库", () -> lastHistory.get() != null, 3000);
            assertEquals(AlertState.FIRING, stateStore.getState(100L, APPID));
        } finally {
            breach.stop();
        }

        Streamer recover = new Streamer(10, () -> asyncExecutor.submit(metricEvent(APPID, "cpu_usage", 10.0)));
        try {
            awaitTrue("恢复后状态清除", () -> stateStore.getState(100L, APPID) == AlertState.IDLE, 5000);
            assertNotNull(lastHistory.get().getResolvedAt(), "历史应被标记恢复");
        } finally {
            recover.stop();
        }
    }

    // ==================== 场景 2: log_keyword 防闪断 ====================
    @Test
    void logKeywordRule_graceWindow_preventsFlapping() {
        MonitorApp app = app();
        AlertRule rule = AlertRule.builder()
                .id(200L).app(app).ruleName("oom-keyword").ruleType("log_keyword")
                .expression("OutOfMemoryError").status("enabled").level("critical").build();
        activeRules.add(rule);
        ruleCache.refresh();

        AtomicBoolean matching = new AtomicBoolean(true);
        Streamer mixed = new Streamer(20, () -> {
            boolean hit = matching.getAndSet(!matching.get());
            asyncExecutor.submit(hit
                    ? logEvent("ERROR", "java.lang.OutOfMemoryError: heap space", "fp-oom")
                    : logEvent("INFO", "normal request ok", "fp-info"));
        });
        try {
            awaitTrue("日志告警 FIRING", () -> stateStore.getState(200L, APPID) == AlertState.FIRING, 5000);
            Thread.sleep(1500);
            assertEquals(AlertState.FIRING, stateStore.getState(200L, APPID),
                    "普通日志混入 1.5s 内不应闪断告警");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            mixed.stop();
        }

        Streamer normalOnly = new Streamer(20, () -> asyncExecutor.submit(logEvent("INFO", "normal request ok", "fp-info")));
        try {
            awaitTrue("持续无命中超过 grace 后恢复", () -> stateStore.getState(200L, APPID) == AlertState.IDLE, 8000);
            assertNotNull(lastHistory.get().getResolvedAt());
        } finally {
            normalOnly.stop();
        }
    }

    // ==================== 场景 3: JEXL 多指标规则不误恢复 ====================
    @Test
    void jexlRule_unrelatedMetric_doesNotResolve() {
        MonitorApp app = app();
        AlertRule rule = AlertRule.builder()
                .id(300L).app(app).ruleName("jexl-ab").ruleType("metric")
                .expression("metric == 'a' && value > 50 || metric == 'b' && value > 50")
                .durationSeconds(1).times(1).status("enabled").build();
        activeRules.add(rule);
        ruleCache.refresh();

        Streamer breach = new Streamer(10, () -> asyncExecutor.submit(metricEvent(APPID, "a", 90.0)));
        try {
            awaitTrue("JEXL 规则 FIRING", () -> stateStore.getState(300L, APPID) == AlertState.FIRING, 5000);
        } finally {
            breach.stop();
        }

        Streamer unrelated = new Streamer(10, () -> asyncExecutor.submit(metricEvent(APPID, "b", 10.0)));
        try {
            Thread.sleep(1200);
            assertEquals(AlertState.FIRING, stateStore.getState(300L, APPID),
                    "不相关 metric 事件不应误恢复 FIRING 告警");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unrelated.stop();
        }

        Streamer sameMetricLow = new Streamer(10, () -> asyncExecutor.submit(metricEvent(APPID, "a", 10.0)));
        try {
            awaitTrue("触发同一 metric 恢复", () -> stateStore.getState(300L, APPID) == AlertState.IDLE, 5000);
        } finally {
            sameMetricLow.stop();
        }
    }

    // ==================== 场景 4: 扫描器兜底恢复 stale FIRING ====================
    @Test
    void staleFiring_scannerForceResolve() {
        MonitorApp app = app();
        AlertRule rule = AlertRule.builder()
                .id(400L).app(app).ruleName("cpu-high2").ruleType("metric")
                .expression("cpu_usage > 80").durationSeconds(0).times(1)
                .status("enabled").build();
        activeRules.add(rule);
        ruleCache.refresh();

        Streamer breach = new Streamer(10, () -> asyncExecutor.submit(metricEvent(APPID, "cpu_usage", 90.0)));
        try {
            awaitTrue("FIRING 状态", () -> stateStore.getState(400L, APPID) == AlertState.FIRING, 5000);
            awaitTrue("告警历史已落库", () -> lastHistory.get() != null, 3000);
        } finally {
            breach.stop();
        }

        // 模拟 PendingStateScanner 发现数据停止上报,触发兜底恢复
        engine.resolveFromScanner(rule, APPID, Instant.now());
        awaitTrue("扫描器兜底恢复后状态清除", () -> stateStore.getState(400L, APPID) == AlertState.IDLE, 3000);
        assertNotNull(lastHistory.get().getResolvedAt(), "stale FIRING 历史应被关闭");
    }

    // ==================== 场景 5: fire 失败回退 + 重试 ====================
    @Test
    void fireFailure_stateRollsBackToPending_thenRetrySucceeds() throws Exception {
        MonitorApp app = app();
        AlertRule rule = AlertRule.builder()
                .id(500L).app(app).ruleName("cpu-high3").ruleType("metric")
                .expression("cpu_usage > 80").durationSeconds(1).times(1)
                .status("enabled").build();
        activeRules.add(rule);
        ruleCache.refresh();

        failFirstSave.set(true);
        Streamer breach = new Streamer(10, () -> asyncExecutor.submit(metricEvent(APPID, "cpu_usage", 90.0)));
        try {
            // 首次 fire 尝试发生在持续超阈值 duration(1s) 之后,先等 save 尝试发生再断言回退
            awaitTrue("save 至少尝试一次(首次fire失败)", () -> saveCalls.get() >= 1, 8000);
            awaitTrue("首次 fire 失败后回退 PENDING", () -> stateStore.getState(500L, APPID) == AlertState.PENDING, 3000);
            awaitTrue("事件流继续后重试成功 FIRING", () -> stateStore.getState(500L, APPID) == AlertState.FIRING, 8000);
        } finally {
            breach.stop();
        }
    }

    // ==================== helpers ====================

    private MonitorApp app() {
        return MonitorApp.builder().appid(APPID).appName("demo-app").build();
    }

    private MetricEvent metricEvent(Long appid, String metric, double value) {
        return MetricEvent.builder()
                .appid(appid).metricName(metric).value(value)
                .timestamp(Instant.now()).build();
    }

    private LogEvent logEvent(String level, String message, String fingerprint) {
        return LogEvent.builder()
                .appid(APPID).level(level).message(message)
                .fingerprint(fingerprint).timestamp(Instant.now()).build();
    }

    private static void awaitTrue(String what, Supplier<Boolean> cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(cond.get())) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待被中断: " + what);
            }
        }
        fail("等待超时: " + what);
    }

    /** 持续向告警引擎灌数据的生产者(模拟上游持续采集上报) */
    private static final class Streamer {
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final AtomicBoolean running = new AtomicBoolean(true);

        Streamer(long intervalMs, Runnable feed) {
            scheduler.scheduleWithFixedDelay(() -> {
                if (running.get()) {
                    feed.run();
                }
            }, 0, intervalMs, TimeUnit.MILLISECONDS);
        }

        void stop() {
            running.set(false);
            scheduler.shutdownNow();
        }
    }
}