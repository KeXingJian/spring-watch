package com.springwatch.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Repository
@RequiredArgsConstructor
public class AppLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public void batchInsert(List<Map<String, Object>> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        log.info("[spring-watch: AppLogRepository 批量写入日志 - count={}]", logs.size());
        String sql = "INSERT INTO app_logs (app_name, level, logger, thread_name, message, throwable, trace_id, log_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.batchUpdate(sql, logs, logs.size(), (ps, log) -> {
            ps.setString(1, (String) log.get("appName"));
            ps.setString(2, (String) log.get("level"));
            ps.setString(3, (String) log.get("logger"));
            ps.setString(4, (String) log.get("threadName"));
            ps.setString(5, (String) log.get("message"));
            ps.setString(6, (String) log.get("throwable"));
            ps.setString(7, (String) log.get("traceId"));
            ps.setTimestamp(8, Timestamp.from((Instant) log.get("logTime")));
        });
        log.info("[spring-watch: AppLogRepository 批量写入日志完成 - count={}]", logs.size());
    }

    public List<Map<String, Object>> queryLogs(String appName, String level, Instant startTime, Instant endTime, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM app_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (appName != null) {
            sql.append(" AND app_name = ?");
            params.add(appName);
        }
        if (level != null) {
            sql.append(" AND level = ?");
            params.add(level);
        }
        if (startTime != null) {
            sql.append(" AND log_time >= ?");
            params.add(Timestamp.from(startTime));
        }
        if (endTime != null) {
            sql.append(" AND log_time <= ?");
            params.add(Timestamp.from(endTime));
        }
        sql.append(" ORDER BY log_time DESC LIMIT ?");
        params.add(limit);

        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }
}