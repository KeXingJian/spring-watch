package com.springwatch.service;

import com.springwatch.repository.AppLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogQueryService {

    private final AppLogRepository appLogRepository;

    public List<Map<String, Object>> queryLogs(String appName, String level,
                                                Instant startTime, Instant endTime, int limit) {
        log.info("[spring-watch: 日志查询 - app={}, level={}, range={}~{}, limit={}]",
                appName, level, startTime, endTime, limit);
        return appLogRepository.queryLogs(appName, level, startTime, endTime, limit);
    }
}