package com.mock.test.service;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserStatsService {

    private final JdbcTemplate jdbcTemplate;

    @WithSpan
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class));
        result.put("activeUsers", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE status = 1", Long.class));
        result.put("lowStockProducts", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM products WHERE stock < ?", Long.class, 100));
        result.put("ordersByStatus", jdbcTemplate.queryForList(
                "SELECT status, COUNT(*) AS cnt FROM orders GROUP BY status"));
        return result;
    }

    @WithSpan
    public Map<String, Object> userOrderSummary(@SpanAttribute("user.id") Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("orderCount", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ?", Long.class, userId));
        result.put("totalSpent", jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_amount), 0) FROM orders WHERE user_id = ? AND status IN ('paid', 'shipped', 'completed')",
                Double.class, userId));
        result.put("recentOrders", jdbcTemplate.queryForList(
                "SELECT id, status, total_amount, create_time FROM orders WHERE user_id = ? ORDER BY id DESC",
                userId));
        return result;
    }
}
