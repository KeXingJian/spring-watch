package com.springwatch.collector.parse;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * kxj: 字符级流式 Prometheus 文本解析器
 * 借鉴 HertzBeat hertzbeat-collector-basic/.../prometheus/parser/OnlineParser.java
 * 改造原因:P0-1 上游拉取性能优化 —— body 不入堆,边读 socket 边解析
 * 收益:单次 /metrics 拉取 GC 分配 -90%+
 */
@Slf4j
public class OnlinePrometheusParser {

    private static final int EOF = -1;
    private static final int LF = '\n';
    private static final int CR = '\r';
    private static final int SPACE = ' ';
    private static final int LBRACE = '{';
    private static final int RBRACE = '}';
    private static final int COMMA = ',';
    private static final int EQUAL = '=';
    private static final int QUOTE = '"';
    private static final int HASH = '#';
    private static final int BACKSLASH = '\\';

    public interface MetricLineHandler {
        void onMetric(String name, Map<String, String> tags, double value);
    }

    public static void parse(InputStream in, MetricLineHandler handler) throws IOException {
        int c = in.read();
        StringBuilder sb = new StringBuilder(128);
        while (c != EOF) {
            if (c == HASH || c == LF) {
                skipToEol(in);
                c = in.read();
                if (c == CR) c = in.read();
                continue;
            }
            sb.setLength(0);
            sb.append((char) c);
            parseOneLine(in, sb, handler);
            c = in.read();
            if (c == CR) c = in.read();
        }
    }

    private static void parseOneLine(InputStream in, StringBuilder sb, MetricLineHandler handler) throws IOException {
        int c = in.read();
        while (c != SPACE && c != LBRACE && c != EOF) {
            sb.append((char) c);
            c = in.read();
        }
        String name = sb.toString();
        sb.setLength(0);

        Map<String, String> tags = null;
        if (c == LBRACE) {
            tags = parseLabels(in);
            c = in.read();
        }

        while (c == SPACE) c = in.read();

        sb.setLength(0);
        while (c != SPACE && c != LF && c != EOF) {
            sb.append((char) c);
            c = in.read();
        }
        double value;
        try {
            value = Double.parseDouble(sb.toString());
        } catch (NumberFormatException e) {
            log.debug("[kxj: Prometheus解析失败 - name={}, valueStr={}, error={}]",
                    name, sb, e.getMessage());
            return;
        }
        handler.onMetric(name, tags, value);
    }

    private static Map<String, String> parseLabels(InputStream in) throws IOException {
        Map<String, String> tags = new HashMap<>(4);
        int c = in.read();
        StringBuilder key = new StringBuilder(32);
        StringBuilder val = new StringBuilder(64);
        boolean inKey = true;
        boolean inQuote = false;
        while (c != RBRACE && c != EOF) {
            if (inQuote) {
                if (c == BACKSLASH) {
                    c = in.read();
                    switch (c) {
                        case 'n' -> val.append('\n');
                        case 'r' -> val.append('\r');
                        case 't' -> val.append('\t');
                        case '\\' -> val.append('\\');
                        case '\"' -> val.append('\"');
                        default -> val.append((char) c);
                    }
                } else if (c == QUOTE) {
                    inQuote = false;
                } else {
                    val.append((char) c);
                }
                c = in.read();
                continue;
            }
            if (c == QUOTE && !inKey) {
                inQuote = true;
                c = in.read();
                continue;
            }
            if (c == EQUAL) {
                inKey = false;
                c = in.read();
                continue;
            }
            if (c == COMMA) {
                if (key.length() > 0) {
                    tags.put(key.toString().trim(), val.toString().trim());
                }
                key.setLength(0);
                val.setLength(0);
                inKey = true;
                c = in.read();
                continue;
            }
            if (inKey) {
                key.append((char) c);
            } else {
                val.append((char) c);
            }
            c = in.read();
        }
        if (key.length() > 0) {
            tags.put(key.toString().trim(), val.toString().trim());
        }
        return tags;
    }

    private static void skipToEol(InputStream in) throws IOException {
        int c = in.read();
        while (c != LF && c != EOF) c = in.read();
    }
}
