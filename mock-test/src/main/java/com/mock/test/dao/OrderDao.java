package com.mock.test.dao;

import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class OrderDao {

    private final ConcurrentHashMap<Long, Map<String, Object>> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(100);

    public OrderDao() {
        store.put(1L, order(1L, 1L, "zhangsan", 8999.00, "paid", List.of(item(1L, "iPhone 15 Pro", 1, 8999.00))));
        store.put(2L, order(2L, 2L, "lisi", 16898.00, "shipped", List.of(item(3L, "AirPods Pro 2", 2, 1899.00), item(4L, "iPad Air 5", 1, 4799.00))));
        store.put(3L, order(3L, 1L, "zhangsan", 14999.00, "completed", List.of(item(2L, "MacBook Pro 14", 1, 14999.00))));
    }

    private Map<String, Object> order(Long id, Long userId, String username, Double total, String status, List<Map<String, Object>> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("userId", userId);
        m.put("username", username);
        m.put("totalAmount", total);
        m.put("status", status);
        m.put("items", items);
        m.put("createTime", LocalDateTime.now().minusHours(id * 3).toString());
        return m;
    }

    private Map<String, Object> item(Long productId, String productName, Integer quantity, Double price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("productName", productName);
        m.put("quantity", quantity);
        m.put("price", price);
        return m;
    }

    public List<Map<String, Object>> findAll() {
        return new ArrayList<>(store.values());
    }

    public List<Map<String, Object>> findByStatus(String status) {
        return store.values().stream()
                .filter(o -> status.equals(o.get("status")))
                .toList();
    }

    public Map<String, Object> findById(Long id) {
        return store.get(id);
    }

    public Map<String, Object> save(Long userId, String username, Double total, String status, List<Map<String, Object>> items) {
        Long id = idGen.getAndIncrement();
        Map<String, Object> o = order(id, userId, username, total, status, items);
        store.put(id, o);
        return o;
    }

    public Map<String, Object> updateStatus(Long id, String status) {
        Map<String, Object> o = store.get(id);
        if (o != null) {
            o.put("status", status);
            switch (status) {
                case "paid" -> o.put("payTime", LocalDateTime.now().toString());
                case "shipped" -> o.put("shipTime", LocalDateTime.now().toString());
                case "completed" -> o.put("completeTime", LocalDateTime.now().toString());
                case "cancelled" -> o.put("cancelTime", LocalDateTime.now().toString());
            }
        }
        return o;
    }

    public Map<String, Object> deleteById(Long id) {
        return store.remove(id);
    }

    public long count() {
        return store.size();
    }

    public double sumTotalAmount() {
        return store.values().stream()
                .mapToDouble(o -> ((Number) o.get("totalAmount")).doubleValue())
                .sum();
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> o : store.values()) {
            String s = (String) o.get("status");
            result.put(s, result.getOrDefault(s, 0L) + 1);
        }
        return result;
    }
}
