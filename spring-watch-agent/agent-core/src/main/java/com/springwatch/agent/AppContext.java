package com.springwatch.agent;

import com.springwatch.agent.config.AgentConfig;
import com.springwatch.agent.http.AgentHttpServer;
import com.springwatch.agent.http.CapabilitiesHandler;
import com.springwatch.agent.http.HealthHandler;
import com.springwatch.agent.http.LogsHandler;
import com.springwatch.agent.http.MetricsHandler;
import com.springwatch.agent.instrument.InstrumentDefinition;
import com.springwatch.agent.instrument.MethodInstrumentation;
import com.springwatch.agent.instrument.web.HttpServerInstrumentation;
import com.springwatch.agent.log.LogAppenderInstaller;
import com.springwatch.agent.log.LogRingBuffer;
import com.springwatch.agent.metric.JvmMetricsProvider;
import com.springwatch.agent.metric.Labels;
import com.springwatch.agent.metric.MetricRegistry;
import com.springwatch.agent.metric.OsMetricsProvider;
import com.springwatch.agent.metric.SwMetricsBridge;
import com.springwatch.agent.sql.JdbcEventExporter;
import net.bytebuddy.agent.builder.AgentBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 全局应用上下文(由 Agent.premain 初始化,所有模块共享)。
 * <p>
 * 单例,生命周期 = JVM 进程;反射 / SPI 注入都基于此。
 */
public final class AppContext {

    private static final Logger LOG = LoggerFactory.getLogger(AppContext.class);

    public static final String VERSION = "1.0.0";

    private static volatile AppContext INSTANCE;

    public final MetricRegistry metrics = new MetricRegistry();
    public final LogRingBuffer logBuffer;
    public final AgentHttpServer httpServer;
    public final JvmMetricsProvider jvmMetrics;
    public final OsMetricsProvider osMetrics;
    public final List<InstrumentDefinition> instruments = new ArrayList<>();
    public JdbcEventExporter jdbcExporter;

    private AppContext() {
        this.jvmMetrics = new JvmMetricsProvider(metrics);
        this.osMetrics = new OsMetricsProvider(metrics);
        this.logBuffer = new LogRingBuffer(AgentConfig.logBufferSize(), this::onLogDropped);
        try {
            this.httpServer = new AgentHttpServer();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create HTTP server", e);
        }
    }

    private void onLogDropped(String level) {
        metrics.counter("sw_log_dropped_total", "Total log events dropped by ring buffer overflow")
                .inc(Labels.of("level", level));
    }

    public static AppContext get() {
        AppContext c = INSTANCE;
        if (c == null) {
            throw new IllegalStateException("AppContext not initialised; call init() first");
        }
        return c;
    }

    public static synchronized AppContext init() {
        if (INSTANCE != null) return INSTANCE;
        AppContext c = new AppContext();
        INSTANCE = c;
        c.jvmMetrics.register();
        if (AgentConfig.isOtelOsEnabled()) {
            c.osMetrics.register();
            LOG.info("[kxj: OsMetricsProvider 启动]");
        }
        c.instruments.add(new MethodInstrumentation(c.metrics));
        if (AgentConfig.isOtelHttpEnabled()) {
            c.instruments.add(new HttpServerInstrumentation(c.metrics));
        }

        for (InstrumentDefinition def : ServiceLoader.load(InstrumentDefinition.class)) {
            c.instruments.add(def);
            LOG.info("[kxj: 加载 InstrumentDefinition - name={}]", def.name());
        }

        LogAppenderInstaller.install(c.logBuffer);

        SwMetricsBridge.register(c.metrics);

        JdbcEventExporter exporter = new JdbcEventExporter(
                c.metrics, AgentConfig.sqlSlowMs(), AgentConfig.sqlDigestLimit());
        exporter.start();
        c.jdbcExporter = exporter;

        c.httpServer
                .route("/metrics", new MetricsHandler(c.metrics))
                .route("/api/agent/logs", new LogsHandler(c.logBuffer, AgentConfig.token()))
                .route("/api/agent/capabilities", new CapabilitiesHandler(
                        VERSION, c.logBuffer, AgentConfig.methodCardinalityLimit(),
                        AgentConfig.sqlSlowMs(), AgentConfig.sqlDigestLimit(), exporter))
                .route("/health", new HealthHandler(c.httpServer.startedAtNanos()))
                .start();

        LOG.info("[kxj: Agent 初始化完成 - 仪器数={}, 日志容量={}, 端口={}, nativeJdbc={}]",
                c.instruments.size(), c.logBuffer.capacity(), c.httpServer.port(),
                JdbcEventExporter.isAvailable());
        return c;
    }

    public static void registerInstrument(InstrumentDefinition def) {
        AppContext c = get();
        c.instruments.add(def);
        LOG.info("[kxj: 外部注册 InstrumentDefinition - name={}]", def.name());
    }

    public AgentBuilder applyTo(AgentBuilder builder) {
        AgentBuilder current = builder;
        for (InstrumentDefinition def : instruments) {
            current = def.apply(current);
        }
        return current;
    }
}
