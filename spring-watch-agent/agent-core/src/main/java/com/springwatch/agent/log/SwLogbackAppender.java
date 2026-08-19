package com.springwatch.agent.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Map;

/**
 * 实际挂载到 logback RootLogger 的 appender。
 * <p>
 * 由 {@link LogAppenderInstaller} 通过反射构造并 start(),
 * 不要求客户在 logback-spring.xml 中手工配置。
 */
public final class SwLogbackAppender extends AppenderBase<ILoggingEvent> {

    private final LogRingBuffer buffer;

    public SwLogbackAppender(LogRingBuffer buffer) {
        this.buffer = buffer;
        setName(LogAppenderInstaller.APPENDER_NAME);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null || buffer == null) {
            return;
        }
        try {
            String level = event.getLevel() == null ? "INFO" : event.getLevel().toString();
            Instant timestamp = Instant.ofEpochMilli(event.getTimeStamp());

            String throwableText = null;
            IThrowableProxy tp = event.getThrowableProxy();
            if (tp != null) {
                throwableText = ThrowableProxyUtil.asString(tp);
            }

            String traceId = MDC.get("trace_id");
            if (traceId == null && event.getMDCPropertyMap() != null) {
                traceId = event.getMDCPropertyMap().get("trace_id");
            }

            LogEvent le = new LogEvent();
            le.setLevel(level);
            le.setLogger(event.getLoggerName());
            le.setThreadName(event.getThreadName());
            le.setMessage(event.getFormattedMessage());
            le.setThrowable(throwableText);
            le.setTraceId(traceId);
            le.setTimestamp(timestamp);

            Map<String, String> ctx = SwLogContextBridge.snapshot();
            if (ctx != null) {
                for (Map.Entry<String, String> e : ctx.entrySet()) {
                    le.putExtra(e.getKey(), e.getValue());
                }
            }

            buffer.append(le);
        } catch (Throwable ignore) {
        }
    }
}
