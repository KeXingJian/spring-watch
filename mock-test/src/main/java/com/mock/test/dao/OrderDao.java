package com.mock.test.dao;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class OrderDao {

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> findAll() {
        List<Map<String, Object>> orders = jdbcTemplate.queryForList("SELECT * FROM orders")
                .stream().map(this::toOrderMap).toList();
        return attachItems(orders);
    }

    public List<Map<String, Object>> findByStatus(String status) {
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT * FROM orders WHERE status = ?", status)
                .stream().map(this::toOrderMap).toList();
        return attachItems(orders);
    }

    public Map<String, Object> findById(Long id) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT * FROM orders WHERE id = ?", id);
        if (list.isEmpty()) return null;
        Map<String, Object> order = toOrderMap(list.get(0));
        order.put("items", findItemsByOrderId(id));
        return order;
    }

    @Transactional
    public Map<String, Object> save(Long userId, String username, Double total,
                                    String status, List<Map<String, Object>> items) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO orders (user_id, username, total_amount, status) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, username);
            ps.setDouble(3, total);
            ps.setString(4, status);
            return ps;
        }, kh);
        Long orderId = kh.getKey().longValue();

        List<Map<String, Object>> savedItems = new ArrayList<>();
        for (Map<String, Object> it : items) {
            jdbcTemplate.update(
                    "INSERT INTO order_items (order_id, product_id, product_name, quantity, price) VALUES (?, ?, ?, ?, ?)",
                    orderId,
                    ((Number) it.get("productId")).longValue(),
                    it.get("productName"),
                    ((Number) it.get("quantity")).intValue(),
                    ((Number) it.get("price")).doubleValue());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", it.get("productId"));
            item.put("productName", it.get("productName"));
            item.put("quantity", it.get("quantity"));
            item.put("price", it.get("price"));
            savedItems.add(item);
        }

        Map<String, Object> order = findById(orderId);
        order.put("items", savedItems);
        return order;
    }

    @Transactional
    public Map<String, Object> updateStatus(Long id, String status) {
        String sql = switch (status) {
            case "paid" -> "UPDATE orders SET status = ?, pay_time = CURRENT_TIMESTAMP WHERE id = ?";
            case "shipped" -> "UPDATE orders SET status = ?, ship_time = CURRENT_TIMESTAMP WHERE id = ?";
            case "completed" -> "UPDATE orders SET status = ?, complete_time = CURRENT_TIMESTAMP WHERE id = ?";
            case "cancelled" -> "UPDATE orders SET status = ?, cancel_time = CURRENT_TIMESTAMP WHERE id = ?";
            default -> "UPDATE orders SET status = ? WHERE id = ?";
        };
        int n = jdbcTemplate.update(sql, status, id);
        if (n == 0) return null;
        return findById(id);
    }

    @Transactional
    public Map<String, Object> deleteById(Long id) {
        Map<String, Object> order = findById(id);
        if (order == null) return null;
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id = ?", id);
        jdbcTemplate.update("DELETE FROM orders WHERE id = ?", id);
        return order;
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        return c == null ? 0L : c;
    }

    public double sumTotalAmount() {
        Double s = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_amount), 0) FROM orders", Double.class);
        return s == null ? 0.0 : s;
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT status, COUNT(*) AS cnt FROM orders GROUP BY status")
                .forEach(row -> result.put(
                        (String) row.get("STATUS"),
                        ((Number) row.get("CNT")).longValue()));
        return result;
    }

    private List<Map<String, Object>> findItemsByOrderId(Long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM order_items WHERE order_id = ? ORDER BY id", orderId)
                .stream().map(this::toItemMap).toList();
    }

    private List<Map<String, Object>> attachItems(List<Map<String, Object>> orders) {
        if (orders.isEmpty()) return orders;
        List<Long> ids = orders.stream().map(o -> ((Number) o.get("id")).longValue()).toList();
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        String inSql = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbcTemplate.queryForList(
                "SELECT * FROM order_items WHERE order_id IN (" + inSql + ") ORDER BY id",
                ids.toArray())
                .forEach(row -> {
                    Long oid = ((Number) row.get("ORDER_ID")).longValue();
                    grouped.computeIfAbsent(oid, k -> new ArrayList<>()).add(toItemMap(row));
                });
        for (Map<String, Object> o : orders) {
            o.put("items", grouped.getOrDefault(o.get("id"), List.of()));
        }
        return orders;
    }

    private Map<String, Object> toOrderMap(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("ID"));
        m.put("userId", row.get("USER_ID"));
        m.put("username", row.get("USERNAME"));
        m.put("totalAmount", row.get("TOTAL_AMOUNT"));
        m.put("status", row.get("STATUS"));
        m.put("createTime", toLocalDateTime(row.get("CREATE_TIME")));
        for (String col : new String[]{"PAY_TIME", "SHIP_TIME", "COMPLETE_TIME", "CANCEL_TIME"}) {
            Object v = row.get(col);
            if (v != null) m.put(toCamel(col), toLocalDateTime(v));
        }
        return m;
    }

    private Map<String, Object> toItemMap(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", row.get("PRODUCT_ID"));
        m.put("productName", row.get("PRODUCT_NAME"));
        m.put("quantity", row.get("QUANTITY"));
        m.put("price", row.get("PRICE"));
        return m;
    }

    private String toLocalDateTime(Object v) {
        return v instanceof Timestamp ts ? ts.toLocalDateTime().toString() : (v == null ? null : v.toString());
    }

    private String toCamel(String s) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : s.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = false;
        }
        return sb.toString();
    }
}
