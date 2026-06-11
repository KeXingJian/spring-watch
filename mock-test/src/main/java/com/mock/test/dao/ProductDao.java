package com.mock.test.dao;

import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class ProductDao {

    private final ConcurrentHashMap<Long, Map<String, Object>> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(100);

    public ProductDao() {
        store.put(1L, product(1L, "iPhone 15 Pro", "手机", 8999.00, 100));
        store.put(2L, product(2L, "MacBook Pro 14", "笔记本", 14999.00, 50));
        store.put(3L, product(3L, "AirPods Pro 2", "耳机", 1899.00, 200));
        store.put(4L, product(4L, "iPad Air 5", "平板", 4799.00, 80));
        store.put(5L, product(5L, "小米14 Ultra", "手机", 6499.00, 150));
    }

    private Map<String, Object> product(Long id, String name, String category, Double price, Integer stock) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("category", category);
        m.put("price", price);
        m.put("stock", stock);
        m.put("status", 1);
        return m;
    }

    public List<Map<String, Object>> findAll() {
        return new ArrayList<>(store.values());
    }

    public List<Map<String, Object>> findByCategory(String category) {
        return store.values().stream()
                .filter(p -> category.equals(p.get("category")))
                .toList();
    }

    public Map<String, Object> findById(Long id) {
        return store.get(id);
    }

    public Map<String, Object> save(String name, String category, Double price, Integer stock) {
        Long id = idGen.getAndIncrement();
        Map<String, Object> p = product(id, name, category, price, stock);
        store.put(id, p);
        return p;
    }

    public long count() {
        return store.size();
    }

    public List<Map<String, Object>> findLowStock(int threshold) {
        return store.values().stream()
                .filter(p -> ((Number) p.get("stock")).intValue() < threshold)
                .toList();
    }
}
