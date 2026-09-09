package com.springwatch.ai.predict;

import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.springwatch.ai.agent.LlmInvoker;
import com.springwatch.model.entity.CapacityPrediction;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.CapacityPredictionRepository;
import com.springwatch.service.MonitorAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * P2 容量预测 - 统计模型先行,LLM 仅解释预测结果。
 * 查询 metrics_5m 降采样桶的近期趋势,用线性/滑动均值回归预测未来值,
 * 判别 OOM / DISK / CPU 场景与风险等级,LLM 失败时降级为统计摘要。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapacityPredictionService {

    private static final String MEASUREMENT = "springboot_metrics";
    private static final double DISK_THRESHOLD = 0.90;
    private static final double CPU_THRESHOLD = 0.85;

    private final QueryApi queryApi;
    private final CapacityPredictionRepository predictionRepository;
    private final MonitorAppService monitorAppService;
    private final LlmInvoker llmInvoker;

    @Value("${influxdb.metrics-downsample-bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String influxOrg;

    @Value("${ai.capacity.prompt:}")
    private String capacityPrompt;

    public PredictionResult predict(Long appid, String metric, int horizonHours) {
        List<Point> points = loadSeries(appid, metric, Instant.now().minusSeconds(6 * 3600), Instant.now());
        if (points.size() < 3) {
            log.debug("[kxj: 容量预测数据不足 - appid={}, metric={}, points={}]", appid, metric, points.size());
            return null;
        }
        LinearFit fit = linearRegression(points);
        double predicted = fit.slope >= 0
                ? fit.lastValue + fit.slope * horizonHours
                : fit.lastValue;
        double current = points.getLast().value;
        String scenario = detectScenario(metric);
        String risk = riskLevel(scenario, predicted, metric);
        String confidence = Math.abs(fit.r2) > 0.7 ? "high" : (Math.abs(fit.r2) > 0.4 ? "medium" : "low");
        String explanation = explain(metric, current, predicted, fit.slope, scenario, risk, horizonHours);
        return new PredictionResult(metric, current, predicted, fit.slope, confidence, scenario, risk, explanation, horizonHours);
    }

    public PredictionResult runAndPersist(Long appid, String metric, int horizonHours) {
        PredictionResult r = predict(appid, metric, horizonHours);
        if (r == null) {
            return null;
        }
        try {
            String appName = monitorAppService.findByAppid(appid).map(MonitorApp::getAppName).orElse(null);
            CapacityPrediction entity = CapacityPrediction.builder()
                    .appid(appid)
                    .appName(appName)
                    .metric(metric)
                    .horizonHours(horizonHours)
                    .currentValue(r.current)
                    .predictedValue(r.predicted)
                    .growthRate(r.slope)
                    .confidence(r.confidence)
                    .scenario(r.scenario)
                    .riskLevel(r.risk)
                    .explanation(r.explanation)
                    .build();
            predictionRepository.save(entity);
            log.info("[kxj: 容量预测落库 - appid={}, metric={}, predicted={}, risk={}]",
                    appid, metric, r.predicted, r.risk);
        } catch (Exception e) {
            log.warn("[kxj: 容量预测落库失败 - appid={}, metric={}, error={}]", appid, metric, e.getMessage());
        }
        return r;
    }

    private List<Point> loadSeries(Long appid, String metric, Instant from, Instant to) {
        List<Point> out = new ArrayList<>();
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "%s" and r.appid == "%d" and r.metric == "%s")
                  |> filter(fn: (r) => exists r._value)
                  |> aggregateWindow(every: 30m, fn: mean, createEmpty: false)
                  |> sort(columns: ["_time"])
                """, bucket, formatInstant(from), formatInstant(to), MEASUREMENT, appid, metric);
        try {
            for (FluxTable t : queryApi.query(flux, influxOrg)) {
                for (FluxRecord r : t.getRecords()) {
                    Object v = r.getValue();
                    Object tm = r.getValueByKey("_time");
                    if (v instanceof Number n && tm instanceof Instant inst) {
                        out.add(new Point(inst.toEpochMilli(), n.doubleValue()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[kxj: 容量预测时序查询失败 - appid={}, metric={}, error={}]", appid, metric, e.getMessage());
        }
        return out;
    }

    private LinearFit linearRegression(List<Point> points) {
        int n = points.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        double firstX = points.get(0).timeMs;
        for (Point p : points) {
            double x = (p.timeMs - firstX) / 3600_000.0; // hours since first
            double y = p.value;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double denom = n * sumXX - sumX * sumX;
        double slope = denom == 0 ? 0 : (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;
        double meanY = sumY / n;
        double ssTot = 0, ssRes = 0;
        for (Point p : points) {
            double x = (p.timeMs - firstX) / 3600_000.0;
            ssTot += (p.value - meanY) * (p.value - meanY);
            ssRes += (p.value - (intercept + slope * x)) * (p.value - (intercept + slope * x));
        }
        double r2 = ssTot == 0 ? 0 : 1 - ssRes / ssTot;
        return new LinearFit(slope, points.getLast().value, r2);
    }

    private String detectScenario(String metric) {
        String m = metric.toLowerCase();
        if (m.contains("heap") || m.contains("memory") || m.contains("jvm")) return "OOM";
        if (m.contains("disk") || m.contains("fs") || m.contains("storage")) return "DISK";
        if (m.contains("cpu")) return "CPU";
        return "OTHER";
    }

    private String riskLevel(String scenario, double predicted, String metric) {
        if ("DISK".equals(scenario)) {
            return predicted >= DISK_THRESHOLD ? "critical" : (predicted >= DISK_THRESHOLD - 0.2 ? "warning" : "safe");
        }
        if ("CPU".equals(scenario)) {
            return predicted >= CPU_THRESHOLD ? "critical" : (predicted >= CPU_THRESHOLD - 0.2 ? "warning" : "safe");
        }
        if (metric.toLowerCase().contains("percent")) {
            return predicted >= 0.85 ? "warning" : "safe";
        }
        return "safe";
    }

    private String explain(String metric, double current, double predicted,
                           double slope, String scenario, String risk, int horizonHours) {
        String fallback = statisticalExplanation(metric, current, predicted, slope, scenario, risk, horizonHours);
        if (capacityPrompt == null || capacityPrompt.isBlank()) {
            return fallback;
        }
        String user = String.format(
                "指标=%s, 当前值=%.2f, 预测值(%.0fh后)=%.2f, 趋势斜率=%.4f/小时, 场景=%s, 风险=%s。请给出简要解释与建议。",
                metric, current, horizonHours, predicted, slope, scenario, risk);
        return llmInvoker.invoke("容量预测解释", capacityPrompt, user, fallback).content();
    }

    private String statisticalExplanation(String metric, double current, double predicted,
                                          double slope, String scenario, String risk, int horizonHours) {
        return String.format(
                "指标 %s 当前值 %.2f,按近期趋势预测 %.0f 小时后约 %.2f(斜率 %.4f/小时)。场景=%s,风险=%s。",
                metric, current, horizonHours, predicted, slope, scenario, risk);
    }

    private String formatInstant(Instant t) {
        return "time(v: " + t + ")";
    }

    public record Point(long timeMs, double value) {}

    public record LinearFit(double slope, double lastValue, double r2) {}

    public record PredictionResult(String metric, double current, double predicted, double slope,
                                   String confidence, String scenario, String risk,
                                   String explanation, int horizonHours) {
    }
}
