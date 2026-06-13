package com.springwatch.web;

import com.springwatch.model.dto.ApiResponse;
import com.springwatch.service.LogQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogQueryService logQueryService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    @GetMapping("/query")
    public ApiResponse<List<Map<String, Object>>> query(
            @RequestParam(required = false) Long appid,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "100") int limit) {
        Instant startTime = start != null ? Instant.parse(start) : Instant.now().minusSeconds(3600);
        Instant endTime = end != null ? Instant.parse(end) : Instant.now();
        log.info("[spring-watch: API查询日志 - appid={}, level={}, range={}~{}, limit={}]",
                appid, level, startTime, endTime, limit);
        return ApiResponse.ok(logQueryService.queryLogs(appid, level, startTime, endTime, limit));
    }

    @GetMapping(value = "/tail", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter tail(@RequestParam Long appid) {
        SseEmitter emitter = new SseEmitter(0L);
        log.info("[spring-watch: SSE日志流连接 - appid={}]", appid);

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                List<Map<String, Object>> recentLogs = logQueryService.queryLogs(
                        appid, null, Instant.now().minusSeconds(5), Instant.now(), 10);
                for (Map<String, Object> logEntry : recentLogs) {
                    emitter.send(SseEmitter.event().name("log").data(logEntry));
                }
            } catch (Exception e) {
                log.debug("[spring-watch: SSE推送异常 - appid={}, error={}]", appid, e.getMessage());
            }
        }, 0, 5, TimeUnit.SECONDS);

        emitter.onCompletion(() -> {
            log.info("[spring-watch: SSE日志流关闭 - appid={}]", appid);
            future.cancel(true);
        });
        emitter.onTimeout(() -> {
            log.info("[spring-watch: SSE日志流超时 - appid={}]", appid);
            future.cancel(true);
        });
        return emitter;
    }
}