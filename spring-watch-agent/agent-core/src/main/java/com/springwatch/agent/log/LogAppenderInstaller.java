package com.springwatch.agent.log;

import com.springwatch.agent.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * 编程式挂载 logback appender(替代 v1.2 客户侧 xml 配置)。
 * <p>
 * 关键点:全部反射调用,避免编译期依赖 logback;appender 通过
 * {@code Instrumentation.appendToSystemClassLoaderSearch} 注入到系统类加载器,
 * 与应用 logback 同加载域,无需任何 xml 改动。
 * <p>
 * logback 缺失时(log4j2 / 其他)静默降级,仅打 warning,业务线程不受影响。
 */
public final class LogAppenderInstaller {

    private static final Logger LOG = LoggerFactory.getLogger(LogAppenderInstaller.class);

    public static final String APPENDER_NAME = "spring-watch";
    public static final String APPENDER_CLASS = "com.springwatch.agent.log.SwLogbackAppender";

    private LogAppenderInstaller() {
    }

    public static void install(LogRingBuffer buffer) {
        if (!AgentConfig.isLogsEnabled()) {
            LOG.info("[kxj: 日志能力已关闭 - spring.watch.disable=logs]");
            return;
        }
        try {
            Class<?> contextClazz = Class.forName("ch.qos.logback.classic.LoggerContext", true, ClassLoader.getSystemClassLoader());
            Class<?> appenderBaseClazz = Class.forName("ch.qos.logback.core.AppenderBase", true, ClassLoader.getSystemClassLoader());
            Class<?> loggerClazz = Class.forName("ch.qos.logback.classic.Logger", true, ClassLoader.getSystemClassLoader());

            Class<?> appenderClazz = Class.forName(APPENDER_CLASS, true, ClassLoader.getSystemClassLoader());
            Object appender = appenderClazz.getConstructor(LogRingBuffer.class).newInstance(buffer);

            Method setContext = appenderBaseClazz.getMethod("setContext", contextClazz);
            Method start = appenderBaseClazz.getMethod("start");
            Method getLogger = contextClazz.getMethod("getLogger", String.class);
            Method addAppender = loggerClazz.getMethod("addAppender", appenderBaseClazz);

            Object context = contextClazz.getMethod("getILoggerFactory").invoke(null);
            Object rootLogger = getLogger.invoke(context, "ROOT");

            setContext.invoke(appender, context);
            start.invoke(appender);
            addAppender.invoke(rootLogger, appender);

            LOG.info("[kxj: logback appender 已挂载 - capacity={}, appender={}]", buffer.capacity(), APPENDER_NAME);
        } catch (ClassNotFoundException e) {
            LOG.warn("[kxj: logback 未在 classpath,日志能力降级 - 业务可能用 log4j2]");
        } catch (Exception e) {
            LOG.warn("[kxj: logback appender 安装失败 - 业务线程不受影响 - error={}]", e.getMessage());
        }
    }
}
