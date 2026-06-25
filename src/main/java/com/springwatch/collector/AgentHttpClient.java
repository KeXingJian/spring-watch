package com.springwatch.collector;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * kxj: 共享 HttpClient 池 - 替代 3 处 new HttpURLConnection
 * JDK 自带 HttpClient 内部维护连接池,支持 HTTP/2,避免每次握手
 *
 * P0-2: 响应体改为 byte[] 接收，超过 maxBodyBytes 直接丢弃，避免单个异常 Agent 撑爆堆。
 */
@Slf4j
@Component
public class AgentHttpClient {

    private final HttpClient httpClient;
    private final int defaultConnectTimeoutMs;
    private final int defaultReadTimeoutMs;
    private final int maxBodyBytes;
    private final ExecutorService executor;

    private final Timer requestTimer;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter timeoutCounter;
    private final Counter non2xxCounter;
    private final Counter bodyTooLargeCounter;
    private final AtomicLong activeRequests = new AtomicLong(0);

    public AgentHttpClient(MeterRegistry registry,
                           @Value("${spring-watch.collector.http.connect-timeout-ms:3000}") int connectTimeoutMs,
                           @Value("${spring-watch.collector.http.read-timeout-ms:10000}") int readTimeoutMs,
                           @Value("${spring-watch.collector.http.max-body-bytes:4194304}") int maxBodyBytes) {
        this.defaultConnectTimeoutMs = connectTimeoutMs;
        this.defaultReadTimeoutMs = readTimeoutMs;
        this.maxBodyBytes = maxBodyBytes;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
        this.requestTimer = Timer.builder("spring.watch.collector.http.request")
                .description("Agent HTTP 请求耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.successCounter = Counter.builder("spring.watch.collector.http.success")
                .description("Agent HTTP 2xx 次数")
                .register(registry);
        this.failureCounter = Counter.builder("spring.watch.collector.http.failure")
                .description("Agent HTTP 异常次数")
                .register(registry);
        this.timeoutCounter = Counter.builder("spring.watch.collector.http.timeout")
                .description("Agent HTTP 超时次数")
                .register(registry);
        this.non2xxCounter = Counter.builder("spring.watch.collector.http.non2xx")
                .description("Agent HTTP 非 2xx 次数")
                .register(registry);
        this.bodyTooLargeCounter = Counter.builder("spring.watch.collector.http.body.rejected")
                .description("Agent HTTP 响应体超过 maxBodyBytes 被拒收的次数")
                .register(registry);
        registry.gauge("spring.watch.collector.http.active", activeRequests);
        log.info("[spring-watch: AgentHttpClient 初始化 - connectTimeout={}ms, readTimeout={}ms, maxBodyBytes={}, pool=virtualThread-per-task]",
                connectTimeoutMs, readTimeoutMs, maxBodyBytes);
    }

    public Result get(String url) {
        return get(url, defaultReadTimeoutMs);
    }

    public Result get(String url, int readTimeoutMs) {
        long start = System.nanoTime();
        activeRequests.incrementAndGet();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            requestTimer.record(java.time.Duration.ofMillis(costMs));
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                successCounter.increment();
                byte[] body = resp.body();
                if (body != null && body.length > maxBodyBytes) {
                    bodyTooLargeCounter.increment();
                    log.warn("[spring-watch: AgentHttp 响应体超限 - url={}, size={}, limit={}]",
                            url, body.length, maxBodyBytes);
                    return Result.failed("body_too_large:" + body.length);
                }
                String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
                return Result.ok(code, text);
            } else {
                non2xxCounter.increment();
                return Result.ok(code, null);
            }
        } catch (java.net.http.HttpTimeoutException e) {
            timeoutCounter.increment();
            failureCounter.increment();
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            requestTimer.record(java.time.Duration.ofMillis(costMs));
            log.debug("[spring-watch: AgentHttp 超时 - url={}, cost={}ms]", url, costMs);
            return Result.failed("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failureCounter.increment();
            return Result.failed("interrupted");
        } catch (Exception e) {
            failureCounter.increment();
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            requestTimer.record(java.time.Duration.ofMillis(costMs));
            log.debug("[spring-watch: AgentHttp 异常 - url={}, cost={}ms, error={}]",
                    url, costMs, e.getMessage());
            return Result.failed(e.getClass().getSimpleName() + ":" + e.getMessage());
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    public boolean reachable(String url, int readTimeoutMs) {
        Result r = get(url, readTimeoutMs);
        return r.isOk() && r.status() == 200;
    }

    public boolean reachable(String url) {
        return reachable(url, defaultReadTimeoutMs);
    }

    @PreDestroy
    void shutdown() {
        log.info("[spring-watch: AgentHttpClient 关闭 - active={}]", activeRequests.get());
        if (executor != null) {
            executor.shutdown();
        }
    }


    public record Result(int status, String body, String error) {
        public static Result ok(int status, String body) {
            return new Result(status, body, null);
        }
        public static Result failed(String error) {
            return new Result(-1, null, error);
        }
        public boolean isOk() {
            return error == null;
        }
        public Optional<String> bodyOpt() {
            return Optional.ofNullable(body);
        }
    }
}
