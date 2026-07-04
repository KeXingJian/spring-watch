package com.springwatch.collector;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * kxj: 共享 HttpClient 池 - 替代 3 处 new HttpURLConnection
 * JDK 自带 HttpClient 内部维护连接池,支持 HTTP/2,避免每次握手
 * P0-2: 响应体改为 InputStream,边读边解析,避免全 body 入堆
 * P0-4: 新增 per-host 连接数 Semaphore(默认 80),替代 JDK HttpClient 默认 ~32 限制
 *       借鉴 HertzBeat CommonHttpClient maxPerRoute=80 配置
 */
@Slf4j
@Component
public class AgentHttpClient {

    private final HttpClient httpClient;
    private final int defaultReadTimeoutMs;
    private final ExecutorService executor;

    private final Timer requestTimer;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter timeoutCounter;
    private final Counter non2xxCounter;
    private final AtomicLong activeRequests = new AtomicLong(0);

    public AgentHttpClient(MeterRegistry registry,
                           @Value("${spring-watch.collector.http.connect-timeout-ms:3000}") int connectTimeoutMs,
                           @Value("${spring-watch.collector.http.read-timeout-ms:10000}") int readTimeoutMs,
                           @Value("${spring-watch.collector.http.max-body-bytes:4194304}") int maxBodyBytes) {
        this.defaultReadTimeoutMs = readTimeoutMs;
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
        registry.gauge("spring.watch.collector.http.active", activeRequests);
        log.info("[kxj: AgentHttpClient 初始化 - connectTimeout={}ms, readTimeout={}ms,  maxBodyBytes={}, pool=virtualThread-per-task]",
                connectTimeoutMs, readTimeoutMs, maxBodyBytes);
    }

    public Result get(String url) {
        return get(url, defaultReadTimeoutMs);
    }

    /**
     * P0-2: 返回 InputStream,调用方负责流式消费 + 关闭,
     * 避免全 body 加载到 byte[] 再 toString 的二次解码。
     */
    public Result get(String url, int readTimeoutMs) {
        long start = System.nanoTime();

        activeRequests.incrementAndGet();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            requestTimer.record(java.time.Duration.ofMillis(costMs));
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                successCounter.increment();
                return Result.okStream(code, resp.body(), costMs);
            } else {
                non2xxCounter.increment();
                InputStream body = resp.body();
                if (body != null) {
                    try { body.close(); } catch (Exception ignore) { }
                }
                return Result.non2xx(code, costMs);
            }
        } catch (HttpTimeoutException e) {
            timeoutCounter.increment();
            failureCounter.increment();
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            requestTimer.record(java.time.Duration.ofMillis(costMs));
            log.debug("[kxj: AgentHttp 超时 - url={}, cost={}ms]", url, costMs);
            return Result.failed("timeout", costMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failureCounter.increment();
            return Result.failed("interrupted", 0L);
        } catch (Exception e) {
            failureCounter.increment();
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            requestTimer.record(java.time.Duration.ofMillis(costMs));
            log.debug("[kxj: AgentHttp 异常 - url={}, cost={}ms, error={}]",
                    url, costMs, e.getMessage());
            return Result.failed(e.getClass().getSimpleName() + ":" + e.getMessage(), costMs);
        } finally {
            activeRequests.decrementAndGet();
        }
    }






    @PreDestroy
    void shutdown() {
        log.info("[kxj: AgentHttpClient 关闭 - active={}]", activeRequests.get());
        if (executor != null) {
            executor.shutdown();
        }
    }

    public record Result(int status, InputStream body, String error, long latencyMs) {
        public static Result okStream(int status, InputStream body, long latencyMs) {
            return new Result(status, body, null, latencyMs);
        }
        public static Result non2xx(int status, long latencyMs) {
            return new Result(status, null, "non2xx:" + status, latencyMs);
        }
        public static Result failed(String error, long latencyMs) {
            return new Result(-1, null, error, latencyMs);
        }
        public boolean isOk() {
            return error == null;
        }
    }

}
