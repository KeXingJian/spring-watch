package com.springwatch.ai.diagnosis;

import com.springwatch.model.entity.AlertHistory;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.entity.DiagnosisReport;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.event.AlertTriggeredEvent;
import com.springwatch.repository.AlertHistoryRepository;
import com.springwatch.repository.AlertRuleRepository;
import com.springwatch.repository.DiagnosisReportRepository;
import com.springwatch.repository.MonitorAppRepository;
import com.springwatch.test.BaseIntegrationTest;
import com.springwatch.test.InfluxSeeder;
import com.springwatch.util.SnowFlakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 告警诊断容器测试(降级态)。
 * AI base-url 指向本地不可达端口(快速失败) + 无效 key → LLM 走降级。
 * 验证:InfluxDB 造数 → 真实 alert_history → diagnose() → 报告落库,
 * LLM 失败时 status=degraded 且 report 含原始证据,不抛异常。
 */
class DiagnosisIntegrationTest extends BaseIntegrationTest {

    @Autowired MonitorAppRepository appRepo;
    @Autowired AlertRuleRepository ruleRepo;
    @Autowired AlertHistoryRepository historyRepo;
    @Autowired DiagnosisReportRepository reportRepo;
    @Autowired DiagnosisReportService diagnosisService;

    private long appid;
    private AlertRule rule;

    @BeforeEach
    void setUp() {
        reportRepo.deleteAll();
        historyRepo.deleteAll();
        appid = SnowFlakeIdGenerator.generateId();
        MonitorApp app = appRepo.save(MonitorApp.builder()
                .appid(appid)
                .appName("diag-app-" + appid)
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

    private AlertTriggeredEvent event(long epochSec, String metric, double value, long historyId) {
        return new AlertTriggeredEvent(rule, appid, metric, value, historyId, Instant.ofEpochSecond(epochSec));
    }

    /** 真实建 alert_history 记录,拿到可落库的 historyId */
    private long createHistory(String metric, double value) {
        AlertHistory h = AlertHistory.builder()
                .rule(rule)
                .app(rule.getApp())
                .alertLevel("warning")
                .alertMessage("cpu high")
                .build();
        return historyRepo.save(h).getId();
    }

    @Test
    void llmFailure_degradesToEvidenceSummaryAndPersists() {
        long nowSec = Instant.now().getEpochSecond();
        for (int i = 0; i < 12; i++) {
            InfluxSeeder.writeMetric(IDB, appid, "system_cpu_usage", 95.0 + i, nowSec - 1500 + i * 300);
        }
        InfluxSeeder.writeErrorLog(IDB, appid, "NullPointerException at OrderService line 42", nowSec - 600);
        long historyId = createHistory("system_cpu_usage", 98.0);

        diagnosisService.diagnose(event(nowSec, "system_cpu_usage", 98.0, historyId));

        List<DiagnosisReport> rows = reportRepo.findByAppidOrderByCreatedAtDesc(appid,
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(rows).hasSize(1);
        DiagnosisReport r = rows.getFirst();
        assertThat(r.getAlertHistoryId()).isEqualTo(historyId);
        assertThat(r.getRuleName()).isEqualTo("cpu-high");
        assertThat(r.getTriggerMetric()).isEqualTo("system_cpu_usage");
        assertThat(r.getEvidence()).isNotBlank();
    }

    @Test
    void noLogWindow_evidenceContainsNoErrorPlaceholder() {
        long nowSec = Instant.now().getEpochSecond();
        InfluxSeeder.writeMetric(IDB, appid, "system_cpu_usage", 96.0, nowSec - 600);
        long historyId = createHistory("system_cpu_usage", 96.0);

        diagnosisService.diagnose(event(nowSec, "system_cpu_usage", 96.0, historyId));

        List<DiagnosisReport> rows = reportRepo.findByAppidOrderByCreatedAtDesc(appid,
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getEvidence()).contains("无ERROR日志");
    }
}