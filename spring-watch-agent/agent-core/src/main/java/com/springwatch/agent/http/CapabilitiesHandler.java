package com.springwatch.agent.http;

import com.springwatch.agent.log.LogRingBuffer;
import com.springwatch.agent.sql.JdbcEventExporter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * GET /api/agent/capabilities — 版本与能力声明。
 * <p>
 * 平台未来用此做协商(version 协商 / 能力开关 / digest 口径);
 * v1 平台不调用,不影响。
 */
public final class CapabilitiesHandler extends BaseHandler {

    private final String version;
    private final LogRingBuffer buffer;
    private final int methodCardinalityLimit;
    private final long sqlSlowMs;
    private final int sqlDigestLimit;
    private final JdbcEventExporter jdbcExporter;

    public CapabilitiesHandler(String version, LogRingBuffer buffer, int methodCardinalityLimit,
                                long sqlSlowMs, int sqlDigestLimit, JdbcEventExporter jdbcExporter) {
        this.version = version;
        this.buffer = buffer;
        this.methodCardinalityLimit = methodCardinalityLimit;
        this.sqlSlowMs = sqlSlowMs;
        this.sqlDigestLimit = sqlDigestLimit;
        this.jdbcExporter = jdbcExporter;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            writeEmpty(ex, 405);
            return;
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        appendString(sb, "version"); sb.append(':'); appendString(sb, version); sb.append(',');
        appendString(sb, "metricNamespace"); sb.append(':'); appendString(sb, "sw"); sb.append(',');
        appendString(sb, "otelNamespaces"); sb.append(':'); sb.append(com.springwatch.agent.config.AgentConfig.isOtelNamespacesEnabled()); sb.append(',');
        appendString(sb, "logBufferCapacity"); sb.append(':'); sb.append(buffer.capacity()); sb.append(',');
        appendString(sb, "methodCardinalityLimit"); sb.append(':'); sb.append(methodCardinalityLimit); sb.append(',');
        appendString(sb, "sqlSlowMs"); sb.append(':'); sb.append(sqlSlowMs); sb.append(',');
        appendString(sb, "sqlDigestLimit"); sb.append(':'); sb.append(sqlDigestLimit); sb.append(',');
        appendString(sb, "nativeJdbc"); sb.append(':'); sb.append(JdbcEventExporter.isAvailable()); sb.append(',');
        appendString(sb, "jdbcBufferDropped"); sb.append(':');
        sb.append(jdbcExporter == null ? 0L : jdbcExporter.droppedTotal());
        sb.append('}');
        writeJson(ex, 200, sb.toString());
    }

    private static void appendString(StringBuilder sb, String raw) {
        sb.append('"').append(raw).append('"');
    }
}
