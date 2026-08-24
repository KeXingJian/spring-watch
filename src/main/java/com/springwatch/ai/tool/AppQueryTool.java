package com.springwatch.ai.tool;

import tools.jackson.databind.ObjectMapper;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.service.MonitorAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppQueryTool {

    private final MonitorAppService monitorAppService;
    private final ObjectMapper objectMapper;

    @Tool(description = "查询被监控应用列表,可按状态过滤(active/paused)。返回 appid/应用名/endpoint/状态/最近心跳")
    public String listApps(
            @ToolParam(description = "状态过滤,active 或 paused,可为空表示全部") String status,
            @ToolParam(description = "最大条数,默认20") Integer limit) {
        log.info("[kxj: AI工具 listApps - status={}, limit={}]", status, limit);
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        Page<MonitorApp> page = "active".equalsIgnoreCase(status) ? monitorAppService.listActive(PageRequest.of(0, size))
                : "paused".equalsIgnoreCase(status) ? monitorAppService.listAll(PageRequest.of(0, size))
                : monitorAppService.listAll(PageRequest.of(0, size));
        List<AppRow> rows = page.getContent().stream().map(AppRow::from).toList();
        return toJson(Map.of("count", page.getTotalElements(), "rows", rows));
    }

    @Tool(description = "查询单个监控应用详情(按 appid),返回注册信息、调度方式与心跳状态")
    public String getApp(
            @ToolParam(description = "监控应用 appid") Long appid) {
        log.info("[kxj: AI工具 getApp - appid={}]", appid);
        Object out = monitorAppService.findByAppid(appid)
                .map(AppDetailRow::from)
                .<Object>map(r -> r)
                .orElse(Map.of("error", "应用不存在: appid=" + appid));
        return toJson(out);
    }

    public record AppRow(Long appid, String appName, String endpoint, Integer metricsPort,
                         String status, Instant lastHeartbeat) {
        static AppRow from(MonitorApp app) {
            return new AppRow(app.getAppid(), app.getAppName(), app.getEndpoint(),
                    app.getMetricsPort(), app.getStatus(), app.getLastHeartbeat());
        }
    }

    public record AppDetailRow(Long appid, String appName, String endpoint, Integer metricsPort,
                               Integer scrapeInterval, String scheduleType, String cronExpression,
                               String labels, String status, Instant lastHeartbeat, Instant lastLogPullTime) {
        static AppDetailRow from(MonitorApp app) {
            return new AppDetailRow(app.getAppid(), app.getAppName(), app.getEndpoint(),
                    app.getMetricsPort(), app.getScrapeInterval(), app.getScheduleType(),
                    app.getCronExpression(), app.getLabels(), app.getStatus(),
                    app.getLastHeartbeat(), app.getLastLogPullTime());
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[kxj: AI工具 JSON序列化失败 - error={}]", e.getMessage());
            return "{}";
        }
    }
}