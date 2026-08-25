package com.springwatch.ai.context;

import com.springwatch.service.LogQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1 日志智能摘要 - LogContextService。
 * 复用 LogQueryService.topFingerprints 聚合某应用在某窗口的 ERROR 指纹 TopN,
 * 供"每日错误摘要 / 周报"定时任务与告警诊断上下文复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogContextService {

    private static final int DEFAULT_TOP_N = 10;

    private final LogQueryService logQueryService;

    /**
     * 聚合指定应用指定窗口的 ERROR 日志指纹 TopN,返回摘要结构。
     * 全应用遍历由调用方(定时任务)负责。
     */
    public AppErrorSummary summarizeAppErrors(Long appid, String appName, Instant from, Instant to, int topN) {
        int safeTop = topN <= 0 ? DEFAULT_TOP_N : Math.min(topN, 20);
        List<LogQueryService.PatternTop> tops = logQueryService.topFingerprints(
                appid, from, to, safeTop, "ERROR");
        long total = tops.stream().mapToLong(LogQueryService.PatternTop::count).sum();
        Map<String, Long> perFingerprint = new LinkedHashMap<>();
        for (LogQueryService.PatternTop t : tops) {
            perFingerprint.put(t.pattern() != null ? t.pattern() : t.fingerprint(), t.count());
        }
        log.debug("[kxj: 日志错误摘要聚合 - appid={}, fingerprints={}, total={}]",
                appid, tops.size(), total);
        return new AppErrorSummary(appid, appName, total, perFingerprint);
    }

    /**
     * 一次性聚合所有应用的错误指纹摘要(用于每日摘要/周报/巡检)。
     */
    public List<AppErrorSummary> summarizeAll(List<Map<String, Object>> apps,
                                              Instant from, Instant to, int topN) {
        List<AppErrorSummary> out = new ArrayList<>();
        if (apps == null) {
            return out;
        }
        for (Map<String, Object> app : apps) {
            Object appidObj = app.get("appid");
            if (appidObj instanceof Number n) {
                long appid = n.longValue();
                String appName = app.get("appName") != null ? app.get("appName").toString() : ("appid=" + appid);
                try {
                    AppErrorSummary s = summarizeAppErrors(appid, appName, from, to, topN);
                    if (s.totalErrors() > 0) {
                        out.add(s);
                    }
                } catch (Exception e) {
                    log.warn("[kxj: 日志摘要聚合失败 - appid={}, error={}]", appid, e.getMessage());
                }
            }
        }
        return out;
    }

    /** 某应用的错误指纹摘要 */
    public record AppErrorSummary(Long appid, String appName, long totalErrors,
                                  Map<String, Long> fingerprintCounts) {
    }
}
