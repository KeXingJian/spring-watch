package com.springwatch.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogFlushScheduler {

    private final LogConsumer logConsumer;

    @Scheduled(fixedDelay = 5000)
    public void flushLogs() {
        log.debug("[spring-watch: LogFlushScheduler 触发日志刷盘]");
        logConsumer.flush();
    }
}