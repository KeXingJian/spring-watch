package com.springwatch.agent.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * GET /health — 存活探针。
 * <p>
 * 不依赖任何服务发现,无鉴权。
 */
public final class HealthHandler extends BaseHandler {

    private final long startedAtNanos;

    public HealthHandler(long startedAtNanos) {
        this.startedAtNanos = startedAtNanos;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            writeEmpty(ex, 405);
            return;
        }
        long uptimeSec = (System.nanoTime() - startedAtNanos) / 1_000_000_000L;
        String body = "{\"status\":\"UP\",\"uptimeSeconds\":" + uptimeSec + "}";
        writeJson(ex, 200, body);
    }
}
