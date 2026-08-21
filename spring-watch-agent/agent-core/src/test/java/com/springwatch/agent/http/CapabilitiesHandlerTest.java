package com.springwatch.agent.http;

import com.springwatch.agent.log.LogRingBuffer;
import com.springwatch.agent.sql.JdbcEventExporter;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilitiesHandlerTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        LogRingBuffer buffer = new LogRingBuffer(65536);
        JdbcEventExporter exporter = new JdbcEventExporter(
                new com.springwatch.agent.metric.MetricRegistry(), 200L, 2000);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/agent/capabilities",
                new CapabilitiesHandler("1.0.0", buffer, 5000, 200L, 2000, exporter));
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void returnsFullCapabilitiesJson() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/api/agent/capabilities")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"version\":\"1.0.0\""));
        assertTrue(resp.body().contains("\"metricNamespace\":\"sw\""));
        assertTrue(resp.body().contains("\"logBufferCapacity\":65536"));
        assertTrue(resp.body().contains("\"methodCardinalityLimit\":5000"));
        assertTrue(resp.body().contains("\"sqlSlowMs\":200"));
        assertTrue(resp.body().contains("\"sqlDigestLimit\":2000"));
        assertTrue(resp.body().contains("\"nativeJdbc\""));
        assertTrue(resp.body().contains("\"jdbcBufferDropped\""));
    }

    @Test
    void rejectsNonGet() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/api/agent/capabilities"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
    }
}
