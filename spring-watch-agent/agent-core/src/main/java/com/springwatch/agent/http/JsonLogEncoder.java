package com.springwatch.agent.http;

import com.springwatch.agent.log.LogEvent;

import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 编码器(无依赖,单线程 Buffer 输出)。
 * <p>
 * 仅支持 LogEvent 字段集合(协议固定),不做泛用 JSON,
 * 避免引入 Jackson/Gson 拖累 Agent 体积。
 */
final class JsonLogEncoder {

    private JsonLogEncoder() {
    }

    static String encode(List<LogEvent> events) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append('[');
        boolean first = true;
        for (LogEvent e : events) {
            if (!first) sb.append(',');
            first = false;
            appendEvent(sb, e);
        }
        sb.append(']');
        return sb.toString();
    }

    private static void appendEvent(StringBuilder sb, LogEvent e) {
        sb.append('{');
        appendField(sb, "level", e.getLevel()); sb.append(',');
        appendField(sb, "logger", e.getLogger()); sb.append(',');
        appendField(sb, "threadName", e.getThreadName()); sb.append(',');
        appendField(sb, "message", e.getMessage()); sb.append(',');
        appendField(sb, "throwable", e.getThrowable()); sb.append(',');
        appendField(sb, "traceId", e.getTraceId()); sb.append(',');
        appendField(sb, "timestamp", e.getTimestamp() == null ? null : e.getTimestamp().toString()); sb.append(',');
        appendField(sb, "host", e.getHost()); sb.append(',');
        appendField(sb, "service", e.getService()); sb.append(',');
        appendField(sb, "method", e.getMethod()); sb.append(',');
        appendField(sb, "env", e.getEnv()); sb.append(',');
        appendField(sb, "appid", e.getAppid() == null ? null : Long.toString(e.getAppid())); sb.append(',');
        appendField(sb, "sequence", Long.toString(e.getSequence()));
        Map<String, String> extras = e.getExtras();
        if (extras != null && !extras.isEmpty()) {
            for (Map.Entry<String, String> en : extras.entrySet()) {
                sb.append(',');
                appendField(sb, en.getKey(), en.getValue());
            }
        }
        sb.append('}');
    }

    private static void appendField(StringBuilder sb, String key, String value) {
        appendString(sb, key);
        sb.append(':');
        appendString(sb, value);
    }

    private static void appendString(StringBuilder sb, String raw) {
        if (raw == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
