package com.springwatch.agent.http;

import com.springwatch.agent.config.AgentConfig;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 内置 HTTP 拉取服务器(JDK HttpServer,零依赖)。
 * <p>
 * 借鉴 OTel PrometheusHttpServer 的
 * host/port/executor/registry 配置装配思路;有界线程池(默认 4),
 * 队列超容保护(防止拉取风暴拖垮业务)。
 */
public final class AgentHttpServer {

    private static final Logger LOG = LoggerFactory.getLogger(AgentHttpServer.class);

    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder rejectedRequests = new LongAdder();
    private final AtomicLong startedAtNanos = new AtomicLong(0);

    public AgentHttpServer() throws IOException {
        this.host = AgentConfig.metricsHost();
        this.port = AgentConfig.metricsPort();
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "sw-agent-http");
                    t.setDaemon(true);
                    return t;
                },
                (r, e) -> {
                    rejectedRequests.increment();
                    LOG.warn("[kxj: HTTP pull 队列满,拒绝请求 - 等待队列长度={}]", e.getQueue().size());
                });
        this.server.setExecutor(executor);
    }

    private final String host;
    private final int port;

    public AgentHttpServer route(String path, HttpHandler handler) {
        server.createContext(path, exchange -> {
            totalRequests.increment();
            handler.handle(exchange);
        });
        return this;
    }

    public AgentHttpServer start() {
        server.start();
        startedAtNanos.set(System.nanoTime());
        LOG.info("[kxj: Agent HTTP 服务启动 - host={}, port={}]", host, port);
        return this;
    }

    public void stop() {
        server.stop(1);
        executor.shutdownNow();
    }

    public long startedAtNanos() {
        return startedAtNanos.get();
    }

    public long totalRequests() {
        return totalRequests.sum();
    }

    public long rejectedRequests() {
        return rejectedRequests.sum();
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }
}
