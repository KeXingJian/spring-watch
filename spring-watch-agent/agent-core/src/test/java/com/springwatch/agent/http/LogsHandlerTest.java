package com.springwatch.agent.http;

import com.springwatch.agent.log.LogEvent;
import com.springwatch.agent.log.LogRingBuffer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogsHandlerTest {

    private HttpServer server;
    private LogRingBuffer buffer;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        buffer = new LogRingBuffer(64);
        for (int i = 0; i < 5; i++) {
            LogEvent e = new LogEvent();
            e.setLevel("INFO");
            e.setMessage("msg-" + i);
            e.setTimestamp(Instant.ofEpochMilli(1700000000000L + i));
            buffer.append(e);
        }
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/agent/logs", new LogsHandler(buffer, null));
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void returnsAllLogsWithCursorHeaders() throws Exception {
        HttpResponse<String> resp = get("/api/agent/logs?since=2020-01-01T00:00:00Z");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().startsWith("["));
        assertTrue(resp.body().contains("\"msg-0\""));
        assertEquals("5", resp.headers().firstValue("X-SW-Log-Cursor").orElse(""));
        assertNotNull(resp.headers().firstValue("X-SW-Log-Dropped"));
    }

    @Test
    void limitApplies() throws Exception {
        HttpResponse<String> resp = get("/api/agent/logs?since=2020-01-01T00:00:00Z&limit=2");
        assertEquals(200, resp.statusCode());
        assertEquals(2, countOccurrences(resp.body(), "\"level\""));
    }

    @Test
    void cursorAdvances() throws Exception {
        HttpResponse<String> first = get("/api/agent/logs?since=2020-01-01T00:00:00Z&limit=2");
        String cursor = first.headers().firstValue("X-SW-Log-Cursor").orElse("");
        HttpResponse<String> second = get("/api/agent/logs?cursor=" + cursor);
        assertEquals(200, second.statusCode());
        assertTrue(second.body().startsWith("["));
    }

    @Test
    void levelFilterWorks() throws Exception {
        LogEvent e = new LogEvent();
        e.setLevel("ERROR");
        e.setMessage("boom");
        e.setTimestamp(Instant.now());
        buffer.append(e);
        HttpResponse<String> resp = get("/api/agent/logs?since=2020-01-01T00:00:00Z&levels=ERROR");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("boom"));
    }

    @Test
    void rejectsNonGet() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/api/agent/logs"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
