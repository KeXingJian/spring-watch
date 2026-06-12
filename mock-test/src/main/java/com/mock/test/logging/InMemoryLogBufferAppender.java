package com.mock.test.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

public class InMemoryLogBufferAppender extends AppenderBase<ILoggingEvent> {

    private static final Deque<LogEvent> BUFFER = new ConcurrentLinkedDeque<>();
    private static final int DEFAULT_MAX_SIZE = 1000;
    private int maxSize = DEFAULT_MAX_SIZE;

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    @Override
    protected void append(ILoggingEvent event) {
        String appName = event.getLoggerContextVO().getProperty("APP_NAME");
        if (appName == null) {
            appName = "mock-test";
        }

        String throwable = null;
        IThrowableProxy tp = event.getThrowableProxy();
        if (tp != null) {
            throwable = ThrowableProxyUtil.asString(tp);
        }

        String traceId = MDC.get("trace_id");

        LogEvent logEvent = LogEvent.builder()
                .appName(appName)
                .level(event.getLevel().toString())
                .logger(event.getLoggerName())
                .threadName(event.getThreadName())
                .message(event.getFormattedMessage())
                .throwable(throwable)
                .traceId(traceId)
                .timestamp(Instant.ofEpochMilli(event.getTimeStamp()))
                .build();

        BUFFER.addFirst(logEvent);
        while (BUFFER.size() > maxSize) {
            BUFFER.pollLast();
        }
    }

    public static List<LogEvent> drainSince(Instant since) {
        List<LogEvent> result = new ArrayList<>();
        for (LogEvent e : BUFFER) {
            if (e.getTimestamp() != null && e.getTimestamp().isAfter(since)) {
                result.add(e);
            }
        }
        return result;
    }

    public static int size() {
        return BUFFER.size();
    }
}
