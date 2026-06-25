package com.mock.test.sim;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

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
            "SELECT status, COUNT(*) AS cnt FROM orders GROUP BY status"
    };

    private final JdbcTemplate jdbcTemplate;

    @Value("${mock.sim.steady-interval-ms:1000}")
    private long intervalMs;

    public SteadyTrafficSimulator(JdbcTemplate jdbcTemplate) {
        super("steady-traffic", "steady");
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected long resolveIntervalMs() {
        return intervalMs;
    }

    @Override
    protected void tick() {
        int burst = ThreadLocalRandom.current().nextInt(1, 6);
        for (int i = 0; i < burst; i++) {
            String sql = READ_SQL[ThreadLocalRandom.current().nextInt(READ_SQL.length)];
            jdbcTemplate.queryForList(sql);
        }
        if (ThreadLocalRandom.current().nextInt(50) == 0) {
            log.info("[kxj: 持续流量] 命中 {} 条只读 SQL", burst);
        }
    }
}
