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
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "100") int limit) {
        Instant startTime = start != null ? Instant.parse(start) : Instant.now().minusSeconds(3600);
        Instant endTime = end != null ? Instant.parse(end) : Instant.now();
        return ApiResponse.ok(logQueryService.queryLogs(app, level, startTime, endTime, limit));
    }

    @GetMapping(value = "/tail", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter tail(@RequestParam String app) {
        SseEmitter emitter = new SseEmitter(0L);
        log.info("[spring-watch: SSE日志流连接 - app={}]", app);

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                List<Map<String, Object>> recentLogs = logQueryService.queryLogs(
                        app, null, Instant.now().minusSeconds(5), Instant.now(), 10);
                for (Map<String, Object> logEntry : recentLogs) {
                    emitter.send(SseEmitter.event().name("log").data(logEntry));
                }
            } catch (Exception e) {
                log.debug("[spring-watch: SSE推送异常 - app={}, error={}]", app, e.getMessage());
            }
        }, 0, 5, TimeUnit.SECONDS);

        emitter.onCompletion(() -> {
            log.info("[spring-watch: SSE日志流关闭 - app={}]", app);
            future.cancel(true);
        });
        emitter.onTimeout(() -> {
            log.info("[spring-watch: SSE日志流超时 - app={}]", app);
            future.cancel(true);
        });
        return emitter;
    }
}