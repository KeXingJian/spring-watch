package com.mock.test.dao;

import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class UserDao {

    private final ConcurrentHashMap<Long, Map<String, Object>> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(100);

    public UserDao() {
        store.put(1L, user(1L, "zhangsan", "张三", "13800138001", 1));
        store.put(2L, user(2L, "lisi", "李四", "13800138002", 1));
        store.put(3L, user(3L, "wangwu", "王五", "13800138003", 0));
    }

    private Map<String, Object> user(Long id, String username, String realName, String phone, Integer status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("username", username);
        m.put("realName", realName);
        m.put("phone", phone);
        m.put("status", status);
        m.put("createTime", LocalDateTime.now().minusDays(id).toString());
        return m;
    }

    public List<Map<String, Object>> findAll() {
        return new ArrayList<>(store.values());
    }

    public Map<String, Object> findById(Long id) {
        return store.get(id);
    }

    public Map<String, Object> save(String username, String realName, String phone) {
        Long id = idGen.getAndIncrement();
        Map<String, Object> u = user(id, username, realName, phone, 1);
        store.put(id, u);
        return u;
    }

    public long count() {
        return store.size();
    }
}
