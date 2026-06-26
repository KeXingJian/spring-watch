package com.mock.test.sim;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@ConditionalOnProperty(name = "mock.sim.enabled", havingValue = "true", matchIfMissing = true)
public class SteadyTrafficSimulator extends BaseSimulator {

    private static final String[] READ_SQL = {
            "SELECT COUNT(*) FROM orders",
            "SELECT COUNT(*) FROM products",
            "SELECT COUNT(*) FROM users",
            "SELECT id, name, price, stock FROM products LIMIT 5",
            "SELECT id, username, status FROM users",
            "SELECT status, COUNT(*) AS cnt FROM orders GROUP BY status",
            "SELECT * FROM order_items WHERE order_id IN (1, 2, 3)",
            "SELECT * FROM products WHERE category = '手机'",
            "SELECT * FROM users WHERE status = 1"
    };

    private static final String[] HTTP_GET_OK = {
            "/api/products",
            "/api/products?page=1&size=10",
            "/api/products?category=手机",
            "/api/users",
            "/api/users/1",
            "/api/orders?page=1&size=10",
            "/api/orders?status=paid",
            "/api/dashboard",
            "/api/ping"
    };

    private static final String[] HTTP_GET_404 = {
            "/api/products/9999",
            "/api/users/9999",
            "/api/orders/9999"
    };

    private static final String[] HTTP_GET_5XX = {
            "/api/error/500",
            "/api/error/503"
    };

    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient;
    private final String selfBase;
    private final AtomicBoolean backgroundStarted = new AtomicBoolean(false);
    private ExecutorService backgroundExec;

    @Value("${mock.sim.steady.interval-ms:1000}")
    private long intervalMs;

    @Value("${mock.sim.steady.batch-min:8}")
    private int batchMin;

    @Value("${mock.sim.steady.batch-max:20}")
    private int batchMax;

    @Value("${mock.sim.steady.jdbc-ratio:0.35}")
    private double jdbcRatio;

    @Value("${mock.sim.steady.http-ratio:0.5}")
    private double httpRatio;

    @Value("${mock.sim.steady.error-ratio:0.1}")
    private double errorRatio;

    @Value("${mock.sim.steady.slow-call-ratio:0.15}")
    private double slowCallRatio;

    @Value("${mock.sim.steady.slow-call-duration-ms:800}")
    private long slowCallDurationMs;

    @Value("${mock.sim.steady.background-slow-threads:3}")
    private int backgroundSlowThreads;

    @Value("${mock.sim.steady.background-slow-min-ms:500}")
    private long backgroundSlowMinMs;

    @Value("${mock.sim.steady.background-slow-max-ms:1500}")
    private long backgroundSlowMaxMs;

    @Value("${server.port:8081}")
    private int serverPort;

    public SteadyTrafficSimulator(JdbcTemplate jdbcTemplate) {
        super("steady-traffic", "steady");
        this.jdbcTemplate = jdbcTemplate;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.selfBase = "http://127.0.0.1";
    }

    @Override
    protected long resolveIntervalMs() {
        return intervalMs;
    }

    @Override
    protected void onStart() {
        if (backgroundSlowThreads > 0 && backgroundStarted.compareAndSet(false, true)) {
            backgroundExec = Executors.newFixedThreadPool(backgroundSlowThreads, r -> {
                Thread t = new Thread(r, "sim-bg-slow");
                t.setDaemon(true);
                return t;
            });
            for (int i = 0; i < backgroundSlowThreads; i++) {
                final int idx = i;
                backgroundExec.submit(() -> backgroundSlowLoop(idx));
            }
            log.info("[kxj: 稳态后台] 启动 background-slow-threads={} range={}~{}ms",
                    backgroundSlowThreads, backgroundSlowMinMs, backgroundSlowMaxMs);
        }
    }

    @Override
    protected void onStop() {
        if (backgroundExec != null) {
            log.info("[kxj: 稳态后台] 关闭 background-slow-threads");
            backgroundExec.shutdownNow();
        }
    }

    private void backgroundSlowLoop(int idx) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (!Thread.currentThread().isInterrupted()) {
            long dur = backgroundSlowMinMs +
                    rnd.nextLong(Math.max(1, backgroundSlowMaxMs - backgroundSlowMinMs + 1));
            try {
                jdbcTemplate.queryForList("SELECT SLEEP(" + dur + ") FROM products WHERE id = " + (1 + (idx % 5)));
            } catch (Exception e) {
                log.debug("[kxj: 稳态后台异常] idx={} err={}", idx, e.getMessage());
            }
            try {
                Thread.sleep(rnd.nextLong(50, 200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    protected void tick() {
        int burst = ThreadLocalRandom.current().nextInt(batchMin, batchMax + 1);
        for (int i = 0; i < burst; i++) {
            double r = ThreadLocalRandom.current().nextDouble();
            if (r < jdbcRatio) {
                runOneJdbc();
            } else if (r < jdbcRatio + httpRatio) {
                runOneHttpOk();
            } else {
                runOneHttpError();
            }
        }
        if (ThreadLocalRandom.current().nextInt(20) == 0) {
            log.info("[kxj: 持续流量] batch={} jdbc={} http={} error={}",
                    burst, Math.round(burst * jdbcRatio),
                    Math.round(burst * httpRatio), Math.round(burst * errorRatio));
        }
    }

    private void runOneJdbc() {
        if (ThreadLocalRandom.current().nextDouble() < slowCallRatio) {
            runSlowJdbc();
            return;
        }
        String sql = READ_SQL[ThreadLocalRandom.current().nextInt(READ_SQL.length)];
        try {
            jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.debug("[kxj: 持续流量-JDBC异常] sql={} err={}", sql, e.getMessage());
        }
    }

    private void runSlowJdbc() {
        try {
            jdbcTemplate.queryForList("SELECT SLEEP(" + slowCallDurationMs + ") FROM products WHERE id = 1");
        } catch (Exception e) {
            log.debug("[kxj: 持续流量-慢SQL异常] err={}", e.getMessage());
        }
    }

    private void runOneHttpOk() {
        String path = HTTP_GET_OK[ThreadLocalRandom.current().nextInt(HTTP_GET_OK.length)];
        sendHttp(path, 200);
    }

    private void runOneHttpError() {
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.6) {
            String path = HTTP_GET_404[ThreadLocalRandom.current().nextInt(HTTP_GET_404.length)];
            sendHttp(path, 404);
        } else {
            String path = HTTP_GET_5XX[ThreadLocalRandom.current().nextInt(HTTP_GET_5XX.length)];
            sendHttp(path, 500);
        }
    }

    private void sendHttp(String path, int expectedStatus) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(selfBase + ":" + serverPort + path))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != expectedStatus / 100 && ThreadLocalRandom.current().nextInt(50) == 0) {
                log.debug("[kxj: HTTP状态不符] path={} expected~{} actual={}",
                        path, expectedStatus, resp.statusCode());
            }
        } catch (Exception e) {
            log.debug("[kxj: HTTP异常] path={} err={}", path, e.getMessage());
        }
    }
}
