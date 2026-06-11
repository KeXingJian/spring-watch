package com.springwatch.consumer;


import com.springwatch.model.event.LogEvent;
import com.springwatch.repository.AppLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogConsumer {

    private final ObjectMapper objectMapper;
    private final AppLogRepository appLogRepository;

    private final List<Map<String, Object>> buffer = new ArrayList<>();

    @KafkaListener(topics = "monitor-logs", groupId = "spring-watch-log-consumer")
    public synchronized void onLog(String message) {
        try {
            LogEvent event = objectMapper.readValue(message, LogEvent.class);
            log.debug("[spring-watch: LogConsumer 收到日志 - app={}, level={}, logger={}]",
                    event.getAppName(), event.getLevel(), event.getLogger());

            Map<String, Object> row = new HashMap<>();
            row.put("appName", event.getAppName());
            row.put("level", event.getLevel());
            row.put("logger", event.getLogger());
            row.put("threadName", event.getThreadName());
            row.put("message", event.getMessage());
            row.put("throwable", event.getThrowable());
            row.put("traceId", event.getTraceId());
            row.put("logTime", event.getTimestamp() != null ? event.getTimestamp() : Instant.now());

            buffer.add(row);
            if (buffer.size() >= 500) {
                flush();
            }
        } catch (Exception e) {
            log.error("[spring-watch: LogConsumer 处理失败 - error={}]", e.getMessage(), e);
        }
    }

    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        log.info("[spring-watch: LogConsumer 批量写入 - count={}]", buffer.size());
        List<Map<String, Object>> batch = new ArrayList<>(buffer);
        buffer.clear();
        appLogRepository.batchInsert(batch);
    }
}