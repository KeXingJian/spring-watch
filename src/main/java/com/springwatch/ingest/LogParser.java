package com.springwatch.ingest;

import com.springwatch.model.event.LogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogParser {

    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "at\\s+([a-zA-Z_$][a-zA-Z0-9_$.]*\\.[a-zA-Z_$][a-zA-Z0-9_$]*)\\(");

    private static final Pattern LOGGER_LAST_SEG = Pattern.compile("([^.]+)$");

    /**
     * kxj: 摄入管道-字段补全 host/service/method/env
     */
    public LogEvent enrich(LogEvent event, String remoteHost, String env) {
        if (event == null) {
            return null;
        }
        if (event.getService() == null && event.getLogger() != null) {
            String logger = event.getLogger();
            int lastDot = logger.lastIndexOf('.');
            if (lastDot > 0 && lastDot < logger.length() - 1) {
                event.setService(logger.substring(lastDot + 1));
            } else {
                Matcher m = LOGGER_LAST_SEG.matcher(logger);
                if (m.find()) {
                    event.setService(m.group(1));
                }
            }
        }
        if (event.getMethod() == null && event.getThrowable() != null) {
            Matcher m = METHOD_PATTERN.matcher(event.getThrowable());
            if (m.find()) {
                event.setMethod(m.group(1));
            }
        }
        if (event.getHost() == null && remoteHost != null) {
            event.setHost(remoteHost);
        }
        if (event.getEnv() == null && env != null) {
            event.setEnv(env);
        }
        return event;
    }
}
