package com.springwatch.alerter;

import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.springwatch.config.InfraAlertsProperties;
import com.springwatch.config.InfraAlertsProperties.Rule;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.repository.MonitorAppRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class InfrastructureAlertScheduler {

    private final QueryApi queryApi;
    private final AlertNotifier alertNotifier;
    private final MonitorAppRepository monitorAppRepository;
    private final InfraAlertsProperties properties;

    @Value("${influxdb.org}")
    private String influxOrg;

    @Value("${influxdb.infra-bucket:infra_metrics}")
    private String infraBucket;

    private final ConcurrentMap<String, Instant> lastFiredAt = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        log.info("[spring-watch: InfrastructureAlertScheduler 初始化 - enabled={}, rules={}, appid={}",
                properties.isEnabled(), properties.getRules().size(), properties.getAppid());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureSelfApp() {
        if (!properties.isEnabled() || properties.getRules().isEmpty()) {
            return;
        }
        long appid = properties.getAppid();
        String baseName = properties.getAppName();
        String name = baseName + "-" + Math.abs(appid);
        try {
            Optional<MonitorApp> byId = monitorAppRepository.findByAppid(appid);
            if (byId.isPresent()) {
                log.info("[spring-watch: 基础设施告警 自监控 app 已存在 - appid={}, name={}]",
                        appid, byId.get().getAppName());
                return;
            }
            MonitorApp app = MonitorApp.builder()
                    .appid(appid)
                    .appName(name)
                    .endpoint("self://infra")
                    .metricsPort(0)
                    .status("active")
                    .build();
            monitorAppRepository.save(app);
            log.info("[spring-watch: 基础设施告警 自监控 app 创建成功 - appid={}, name={}]", appid, name);
        } catch (Exception e) {
            log.warn("[spring-watch: 基础设施告警 自监控 app 创建失败 - error={}]", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${spring-watch.infra-alerts.poll-interval-ms:60000}")
    public void evaluate() {
        if (!properties.isEnabled() || properties.getRules().isEmpty()) {
            return;
        }
        for (Rule rule : properties.getRules()) {
            try {
                evaluateOne(rule);
            } catch (Throwable t) {
                log.warn("[spring-watch: 基础设施告警评估异常 - rule={}, error={}]",
                        rule.getName(), t.getMessage());
            }
        }
    }

    private void evaluateOne(Rule rule) {
        Double value = queryLatest(rule.getComponent(), rule.getMetric(), rule.getTag());
        if (value == null) {
            return;
        }
        boolean breached;
        String op = rule.getOp() == null ? ">" : rule.getOp();
        switch (op) {
            case ">=" -> breached = value >= rule.getThreshold();
            case "<" -> breached = value < rule.getThreshold();
            case "<=" -> breached = value <= rule.getThreshold();
            case "==" -> breached = Math.abs(value - rule.getThreshold()) < 1e-9;
            case "!=" -> breached = Math.abs(value - rule.getThreshold()) >= 1e-9;
            default -> breached = value > rule.getThreshold();
        }
        if (!breached) {
            return;
        }
        Instant now = Instant.now();
        Instant last = lastFiredAt.get(rule.getName());
        if (last != null && now.toEpochMilli() - last.toEpochMilli() < rule.getCooldownSeconds() * 1000L) {
            return;
        }
        lastFiredAt.put(rule.getName(), now);
        log.warn("[spring-watch: 基础设施告警触发 - rule={}, value={}, threshold={}, op={}",
                rule.getName(), value, rule.getThreshold(), op);

        long appid = properties.getAppid();
        AlertRule alertRule = AlertRule.builder()
                .id(0L)
                .app(monitorAppRepository.findByAppid(appid).orElse(null))
                .ruleName("[infra] " + rule.getName())
                .ruleType("metric")
                .expression(rule.getExpression() == null
                        ? "value " + op + " " + rule.getThreshold()
                        : rule.getExpression())
                .level(rule.getLevel() == null ? "warning" : rule.getLevel())
                .durationSeconds(0)
                .times(1)
                .status("enabled")
                .build();
        MetricEvent synthetic = MetricEvent.builder()
                .appid(appid)
                .metricName(rule.getMetric())
                .value(value)
                .timestamp(now)
                .tags(rule.getTag())
                .build();
        try {
            alertNotifier.notify(alertRule, synthetic, "infra_alert");
        } catch (Exception e) {
            log.warn("[spring-watch: 基础设施告警 邮件发送失败 - rule={}, error={}]",
                    rule.getName(), e.getMessage());
        }
    }

    private Double queryLatest(String component, String metric, java.util.Map<String, String> tag) {
        StringBuilder filter = new StringBuilder();
        filter.append("r.component == \"").append(component).append("\"");
        filter.append(" and r.metric == \"").append(metric).append("\"");
        if (tag != null) {
            for (var e : tag.entrySet()) {
                filter.append(" and r.").append(e.getKey()).append(" == \"").append(e.getValue()).append("\"");
            }
        }
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: -5m)
                  |> filter(fn: (r) => %s)
                  |> last()
                """, infraBucket, filter);
        try {
            List<FluxTable> tables = queryApi.query(flux, influxOrg);
            for (FluxTable t : tables) {
                for (FluxRecord r : t.getRecords()) {
                    Object v = r.getValue();
                    if (v instanceof Number n) return n.doubleValue();
                    if (v != null) {
                        try {
                            return Double.parseDouble(v.toString());
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[spring-watch: infra 查询最新值失败 - component={}, metric={}, error={}]",
                    component, metric, e.getMessage());
        }
        return null;
    }
}
