package com.springwatch.ai.diagnosis;

import tools.jackson.databind.ObjectMapper;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.entity.DiagnosisReport;
import com.springwatch.model.event.AlertTriggeredEvent;
import com.springwatch.repository.DiagnosisReportRepository;
import com.springwatch.service.LogQueryService;
import com.springwatch.service.MetricQueryService;
import com.springwatch.service.MonitorAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisReportService {

    private static final int EVIDENCE_WINDOW_MINUTES = 30;
    private static final int TOP_FINGERPRINTS = 10;

    private final DiagnosisReportRepository reportRepository;
    private final MetricQueryService metricQueryService;
    private final LogQueryService logQueryService;
    private final MonitorAppService monitorAppService;
    private final ChatClient aiChatClient;
    private final ObjectMapper objectMapper;
    private final com.springwatch.ai.rag.DocEmbeddingService docEmbeddingService;

    @Value("${ai.diagnosis.prompt}")
    private String diagnosisPrompt;

    /**
     * 告警 FIRING 诊断:拉取前后 30min 指标窗口 + 错误日志 TopN 作为证据,
     * LLM 生成根因分析报告并落库。LLM 失败时降级为原始证据摘要,不抛异常。
     */
    public void diagnose(AlertTriggeredEvent event) {
        AlertRule rule = event.getRule();
        Long appid = event.getAppid();
        Instant windowFrom = event.getTriggeredAt().minus(Duration.ofMinutes(EVIDENCE_WINDOW_MINUTES));
        Instant windowTo = event.getTriggeredAt().plus(Duration.ofMinutes(EVIDENCE_WINDOW_MINUTES));
        log.info("[kxj: 告警诊断开始 - historyId={}, appid={}, rule={}, metric={}, value={}]",
                event.getHistoryId(), appid, rule.getRuleName(), event.getMetric(), event.getValue());

        String evidence = buildEvidence(event, appid, windowFrom, windowTo);

        DiagnosisReport.DiagnosisReportBuilder builder = DiagnosisReport.builder()
                .alertHistoryId(event.getHistoryId())
                .appid(appid)
                .ruleId(rule.getId())
                .ruleName(rule.getRuleName())
                .alertLevel(rule.getLevel())
                .triggerMetric(event.getMetric())
                .triggerValue(event.getValue())
                .evidence(evidence);

        try {
            String report = generateReport(event, evidence);
            builder.report(report).status("success");
        } catch (Exception e) {
            log.warn("[kxj: 告警诊断LLM失败,降级为证据摘要 - historyId={}, error={}]", event.getHistoryId(), e.getMessage());
            builder.report("LLM 诊断失败,以下为原始证据摘要:\n\n" + evidence)
                    .status("degraded")
                    .errorMsg(truncate(e.getMessage(), 500));
        }

        try {
            DiagnosisReport saved = reportRepository.save(builder.build());
            log.info("[kxj: 告警诊断报告落库 - historyId={}, appid={}]", event.getHistoryId(), appid);
            feedKnowledgeBase(saved);
        } catch (Exception e) {
            log.warn("[kxj: 告警诊断报告落库失败 - historyId={}, error={}]", event.getHistoryId(), e.getMessage());
        }
    }

    /**
     * P2 RAG 回灌:诊断报告切片入知识库(越用越准)。
     * 异步在虚拟线程执行,失败不影响主链路。
     */
    private void feedKnowledgeBase(DiagnosisReport report) {
        if (report == null || report.getReport() == null || report.getReport().isBlank()) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                String content = "规则: " + report.getRuleName()
                        + "\n指标: " + report.getTriggerMetric()
                        + " = " + report.getTriggerValue()
                        + "\n诊断报告:\n" + report.getReport();
                docEmbeddingService.ingestDocument("diagnosis_report",
                        String.valueOf(report.getId()), report.getRuleName(), content);
            } catch (Exception e) {
                log.debug("[kxj: 诊断报告回灌知识库失败 - reportId={}, error={}]", report.getId(), e.getMessage());
            }
        });
    }

    private String buildEvidence(AlertTriggeredEvent event, Long appid, Instant from, Instant to) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("trigger", Map.of(
                "ruleName", event.getRule().getRuleName(),
                "ruleType", event.getRule().getRuleType(),
                "expression", event.getRule().getExpression(),
                "metric", event.getMetric(),
                "value", event.getValue(),
                "triggeredAt", event.getTriggeredAt().toString()));

        monitorAppService.findByAppid(appid).ifPresent(app -> evidence.put("app", appSummary(app)));

        String metric = event.getMetric();
        boolean isSynthetic = metric == null || metric.startsWith("log_");
        if (!isSynthetic) {
            Map<String, Object> series = metricQueryService.querySeries(
                    appid, metric, from, to, "mean", "1m", Map.of());
            evidence.put("metricWindow30m", summarizeSeries(series));
        } else {
            evidence.put("metricWindow30m", "合成指标(日志类),不单独查询指标时序");
        }

        List<LogQueryService.PatternTop> tops = logQueryService.topFingerprints(
                appid, from, to, TOP_FINGERPRINTS, "ERROR");
        evidence.put("errorLogTop" + TOP_FINGERPRINTS, tops.isEmpty() ? "无ERROR日志" : tops);
        return toJson(evidence);
    }

    private Map<String, Object> appSummary(MonitorApp app) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appid", app.getAppid());
        out.put("appName", app.getAppName());
        out.put("endpoint", app.getEndpoint());
        out.put("status", app.getStatus());
        out.put("lastHeartbeat", app.getLastHeartbeat());
        return out;
    }

    /** 时序摘要化:min/max/avg + 首尾点,控制 token 成本,不灌原始序列 */
    private Map<String, Object> summarizeSeries(Map<String, Object> series) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = (List<Map<String, Object>>) series.get("series");
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            out.put("summary", "窗口内无数据");
            return out;
        }
        List<Map<String, Object>> points = raw.getFirst().get("points") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        out.put("seriesCount", raw.size());
        out.put("pointCount", points.size());
        if (!points.isEmpty()) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
            for (Map<String, Object> p : points) {
                Object v = p.get("v");
                if (v instanceof Number n) {
                    double d = n.doubleValue();
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                    sum += d;
                }
            }
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("min", min == Double.MAX_VALUE ? null : min);
            stat.put("max", max == -Double.MAX_VALUE ? null : max);
            stat.put("avg", points.isEmpty() ? null : sum / points.size());
            stat.put("first", points.getFirst());
            stat.put("last", points.getLast());
            out.put("stat", stat);
        }
        return out;
    }

    private String generateReport(AlertTriggeredEvent event, String evidence) {
        String user = String.format(
                "告警信息:规则=%s, appid=%s, 指标=%s, 触发值=%s, 触发时间=%s\n\n证据数据:\n%s",
                event.getRule().getRuleName(), event.getAppid(),
                event.getMetric(), event.getValue(), event.getTriggeredAt(), evidence);
        String report = aiChatClient.prompt()
                .system(diagnosisPrompt)
                .user(user)
                .call()
                .content();
        return report == null ? "(空回复)" : report;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[kxj: 证据序列化失败 - error={}]", e.getMessage());
            return obj == null ? "{}" : obj.toString();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}