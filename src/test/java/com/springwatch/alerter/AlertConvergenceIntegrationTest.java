package com.springwatch.alerter;

import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.AlertHistoryRepository;
import com.springwatch.repository.AlertRuleRepository;
import com.springwatch.repository.MonitorAppRepository;
import com.springwatch.test.BaseIntegrationTest;
import com.springwatch.util.SnowFlakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3 告警收敛容器测试 - 真实 PG 落库验证。
 * 同类连续 fire 只首报通知,其余抑制;跨指标分组;收敛字段落库正确。
 */
@SpringBootTest
class AlertConvergenceIntegrationTest extends BaseIntegrationTest {

    @Autowired MonitorAppRepository appRepo;
    @Autowired AlertRuleRepository ruleRepo;
    @Autowired AlertHistoryRepository historyRepo;
    @Autowired AlertLifecycleService lifecycle;

    private long appid;
    private AlertRule rule;

    @BeforeEach
    void setUp() {
        historyRepo.deleteAll();
        appid = SnowFlakeIdGenerator.generateId();
        MonitorApp app = appRepo.save(MonitorApp.builder()
                .appid(appid)
                .appName("conv-app-" + appid)
                .endpoint("http://localhost:1")
                .metricsPort(9464)
                .scrapeInterval(30)
                .scheduleType("INTERVAL")
                .status("active")
                .build());
        rule = ruleRepo.save(AlertRule.builder()
                .app(app)
                .ruleName("cpu-high")
                .ruleType("metric")
                .expression("value > 90")
                .thresholdValue(null)
                .durationSeconds(0)
                .times(1)
                .level("warning")
                .status("enabled")
                .build());
    }

    private void fire(double value) {
        lifecycle.fire(rule, MetricEvent.builder()
                .appid(appid)
                .metricName("system_cpu_usage")
                .value(value)
                .timestamp(Instant.now())
                .build());
    }

    @Test
    void sameKeyTenFires_onlyLeaderNotifiedOthersSuppressed() {
        for (int i = 0; i < 10; i++) {
            fire(99.0);
        }
        List<AlertHistory> rows = historyRepo.findAll();
        assertThat(rows).hasSize(10);

        long leaders = rows.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getAggSuppressed()))
                .count();
        assertThat(leaders).isEqualTo(1);

        long leadersByRole = rows.stream()
                .filter(r -> "leader".equals(r.getAggRole()))
                .count();
        assertThat(leadersByRole).isEqualTo(1);

        long members = rows.stream()
                .filter(r -> Boolean.TRUE.equals(r.getAggSuppressed()))
                .count();
        assertThat(members).isEqualTo(9);

        assertThat(rows.stream().map(AlertHistory::getAggGroupId).distinct()).hasSize(1);
        // 组累计次数 = 最后一条 fire 时的组内计数(10)
        assertThat(rows.stream().mapToInt(r -> r.getAggGroupCount() == null ? 0 : r.getAggGroupCount()).max())
                .hasValue(10);
    }

    @Test
    void differentMetric_producesTwoConvergenceGroups() {
        fire(99.0);
        lifecycle.fire(rule, MetricEvent.builder()
                .appid(appid)
                .metricName("jvm_memory_used_bytes")
                .value(9e8)
                .timestamp(Instant.now())
                .build());
        List<AlertHistory> rows = historyRepo.findAll();
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(AlertHistory::getAggGroupId).distinct()).hasSize(2);
    }
}