package com.springwatch.agent.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 共享基础:鉴权、CORS、JSON/Text 响应头、参数解析。
 * <p>
 * 借鉴 OTel PrometheusHttpServer 的 host/port/executor 装配思路,但
 * 使用 JDK 内置 {@code com.sun.net.httpserver.HttpServer}(零依赖)。
 */
public abstract class BaseHandler implements HttpHandler {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected void writeText(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    protected void writeJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    protected void writeEmpty(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
    }

    protected Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                map.put(java.net.URLDecoder.decode(pair, java.nio.charset.StandardCharsets.UTF_8), "");
            } else {
                map.put(
                        java.net.URLDecoder.decode(pair.substring(0, idx), java.nio.charset.StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(idx + 1), java.nio.charset.StandardCharsets.UTF_8)
                );
            }
        }
        return map;
    }

    protected boolean checkToken(HttpExchange ex, String expected) {
        if (expected == null || expected.isEmpty()) return true;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null) return false;
        return ("Bearer " + expected).equals(auth);
    }
}
