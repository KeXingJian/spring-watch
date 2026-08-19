package com.springwatch.agent.http;

import com.springwatch.agent.log.LogEvent;
import com.springwatch.agent.log.LogRingBuffer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET /api/agent/logs?since=<ISO>&cursor=<seq>&limit=N&levels=WARN,ERROR。
 * <p>
 * 协议与平台 {@code AgentLogCollector} 严格兼容(JSON 数组 + LogEvent 字段)。
 * 新增可选游标参数 cursor=<seq>(向前兼容)。
 */
public final class LogsHandler extends BaseHandler {

    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_LIMIT = 10000;

    private final LogRingBuffer buffer;
    private final String token;

    public LogsHandler(LogRingBuffer buffer, String token) {
        this.buffer = buffer;
        this.token = token;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            writeEmpty(ex, 405);
            return;
        }
        if (!checkToken(ex, token)) {
            writeEmpty(ex, 401);
            return;
        }
        try {
            Map<String, String> params = parseQuery(ex.getRequestURI().getRawQuery());
            Instant since = parseSince(params.get("since"));
            Long cursorSeq = parseLong(params.get("cursor"));
            int limit = parseInt(params.get("limit"), DEFAULT_LIMIT);
            if (limit > MAX_LIMIT) limit = MAX_LIMIT;
            if (limit < 1) limit = 1;
            List<String> levels = parseLevels(params.get("levels"));

            LogRingBuffer.InstantCursor cursor;
            if (cursorSeq != null) {
                cursor = LogRingBuffer.InstantCursor.ofSequence(cursorSeq);
            } else {
                cursor = LogRingBuffer.InstantCursor.ofTimestamp(since);
            }

            LogRingBuffer.Snapshot snapshot = buffer.snapshotSince(cursor);
            List<LogEvent> events = snapshot.events;
            if (!levels.isEmpty()) {
                events = events.stream().filter(e -> levels.contains(e.getLevel())).toList();
            }
            if (events.size() > limit) {
                events = events.subList(0, limit);
            }

            ex.getResponseHeaders().add("X-SW-Log-Cursor", String.valueOf(snapshot.headSequence));
            ex.getResponseHeaders().add("X-SW-Log-Tail", String.valueOf(snapshot.tailSequence));
            ex.getResponseHeaders().add("X-SW-Log-Dropped", String.valueOf(buffer.droppedTotal()));

            String json = JsonLogEncoder.encode(events);
            writeJson(ex, 200, json);
        } catch (Exception e) {
            log.warn("[kxj: /api/agent/logs 异常 - error={}]", e.getMessage());
            writeEmpty(ex, 500);
        }
    }

    private Instant parseSince(String raw) {
        if (raw == null || raw.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    private Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseInt(String raw, int def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private List<String> parseLevels(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return List.of(raw.split(","));
    }
}
