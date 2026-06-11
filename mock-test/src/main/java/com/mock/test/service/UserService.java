package com.mock.test.service;

import com.mock.test.dao.UserDao;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserService {

    private final UserDao userDao;

    public UserService(UserDao userDao) {
        this.userDao = userDao;
    }

    @WithSpan
    public Map<String, Object> listUsers(int page, int size) {
        List<Map<String, Object>> list = userDao.findAll();
        int from = Math.min((page - 1) * size, list.size());
        int to = Math.min(from + size, list.size());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list.subList(from, to));
        data.put("total", list.size());
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    @WithSpan
    public Map<String, Object> getUser(Long id) {
        return userDao.findById(id);
    }

    @WithSpan
    public Map<String, Object> createUser(Map<String, Object> body) {
        return userDao.save(
                (String) body.getOrDefault("username", "user"),
                (String) body.getOrDefault("realName", "用户"),
                (String) body.getOrDefault("phone", "13800000000")
        );
    }

    @WithSpan
    public long count() {
        return userDao.count();
    }
}
