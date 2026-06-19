package com.mock.test.dao;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class UserDao {

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> findAll() {
        return jdbcTemplate.queryForList("SELECT * FROM users");
    }

    public Map<String, Object> findById(Long id) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT * FROM users WHERE id = ?", id);
        return list.isEmpty() ? null : toUserMap(list.get(0));
    }

    public Map<String, Object> save(String username, String realName, String phone) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO users (username, real_name, phone, status) VALUES (?, ?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, realName);
            ps.setString(3, phone);
            return ps;
        }, kh);
        Long id = kh.getKey().longValue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("username", username);
        m.put("realName", realName);
        m.put("phone", phone);
        m.put("status", 1);
        m.put("createTime", LocalDateTime.now().toString());
        return m;
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return c == null ? 0L : c;
    }

    private Map<String, Object> toUserMap(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("ID"));
        m.put("username", row.get("USERNAME"));
        m.put("realName", row.get("REAL_NAME"));
        m.put("phone", row.get("PHONE"));
        m.put("status", row.get("STATUS"));
        Object t = row.get("CREATE_TIME");
        m.put("createTime", t instanceof Timestamp ts ? ts.toLocalDateTime().toString() : t);
        return m;
    }
}
