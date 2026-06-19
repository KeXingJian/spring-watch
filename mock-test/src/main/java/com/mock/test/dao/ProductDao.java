package com.mock.test.dao;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class ProductDao {

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> findAll() {
        return jdbcTemplate.queryForList("SELECT * FROM products")
                .stream().map(this::toProductMap).toList();
    }

    public List<Map<String, Object>> findByCategory(String category) {
        return jdbcTemplate.queryForList("SELECT * FROM products WHERE category = ?", category)
                .stream().map(this::toProductMap).toList();
    }

    public Map<String, Object> findById(Long id) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT * FROM products WHERE id = ?", id);
        return list.isEmpty() ? null : toProductMap(list.get(0));
    }

    public Map<String, Object> save(String name, String category, Double price, Integer stock) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO products (name, category, price, stock, status) VALUES (?, ?, ?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, category);
            ps.setDouble(3, price);
            ps.setInt(4, stock);
            return ps;
        }, kh);
        Long id = kh.getKey().longValue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("category", category);
        m.put("price", price);
        m.put("stock", stock);
        m.put("status", 1);
        return m;
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class);
        return c == null ? 0L : c;
    }

    public List<Map<String, Object>> findLowStock(int threshold) {
        return jdbcTemplate.queryForList("SELECT * FROM products WHERE stock < ?", threshold)
                .stream().map(this::toProductMap).toList();
    }

    private Map<String, Object> toProductMap(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("ID"));
        m.put("name", row.get("NAME"));
        m.put("category", row.get("CATEGORY"));
        m.put("price", row.get("PRICE"));
        m.put("stock", row.get("STOCK"));
        m.put("status", row.get("STATUS"));
        return m;
    }
}
