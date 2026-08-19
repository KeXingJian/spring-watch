package com.springwatch.agent.log;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 拉取端线协议对象,与平台 LogEvent 字段对齐(只多不少)。
 * <p>
 * 字段命名严格遵循平台 AgentLogCollector 解析约定,新增字段使用
 * extras 容器,避免破坏 JSON 字段集合。
 */
public final class LogEvent {

    private Long appid;
    private String level;
    private String logger;
    private String threadName;
    private String message;
    private String throwable;
    private String traceId;
    private Instant timestamp;
    private String host;
    private String service;
    private String method;
    private String env;
    private long sequence;
    private Map<String, String> extras;

    public Long getAppid() { return appid; }
    public void setAppid(Long appid) { this.appid = appid; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getLogger() { return logger; }
    public void setLogger(String logger) { this.logger = logger; }

    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getThrowable() { return throwable; }
    public void setThrowable(String throwable) { this.throwable = throwable; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }

    public Map<String, String> getExtras() { return extras; }
    public void setExtras(Map<String, String> extras) { this.extras = extras; }

    public void putExtra(String key, String value) {
        if (key == null || value == null) return;
        if (extras == null) extras = new LinkedHashMap<>();
        extras.put(key, value);
    }
}
