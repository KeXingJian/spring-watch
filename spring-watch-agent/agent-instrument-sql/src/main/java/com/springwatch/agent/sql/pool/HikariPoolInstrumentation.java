package com.springwatch.agent.sql.pool;

import com.springwatch.agent.config.AgentConfig;
import com.springwatch.agent.instrument.InstrumentDefinition;
import com.springwatch.agent.metric.MetricRegistry;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;


/**
 * HikariCP 连接池指标拦截 + 仪表化。
 * <p>
 * 织入点:
 * <ul>
 *   <li>{@code com.zaxxer.hikari.HikariDataSource.<init>} — 捕获所有 DataSource 实例,
 *       立即注册基于 {@code HikariDataSource#getHikariPoolMXBean()} 的 gauge 系列,
 *       每次 scrape 现读。</li>
 *   <li>{@code com.zaxxer.hikari.HikariDataSource.getConnection()} 入口 — 记录起始时刻</li>
 *   <li>{@code com.zaxxer.hikari.proxy.HikariProxyConnection.close()} 出口 — 计算 use 时长</li>
 * </ul>
 * 命名对齐平台前端 {@code useAppView.ts:jdbcViewSpecs()}:
 * <pre>
 * db_client_connections_max{gauge, pool.name}
 * db_client_connections_min{gauge, pool.name}
 * db_client_connections_idle_min{gauge, pool.name}
 * db_client_connections_usage{gauge, pool.name, state="used|idle"}
 * db_client_connections_pending_requests{gauge, pool.name}
 * db_client_connections_use_time_milliseconds{histogram, pool.name}
 * </pre>
 * 不织入时(无 Hikari 依赖):指标不注册,前端空数据。
 * <p>
 * 关闭: {@code -Dspring.watch.disable=sql} 同时屏蔽 SQL 与本 instrumentation;
 * 或 {@code -Dspring.watch.otel.namespaces=false} 单独关 OTel 命名空间。
 */
public final class HikariPoolInstrumentation implements InstrumentDefinition {

    private static final String DESC_HIKARI_DS = "com.zaxxer.hikari.HikariDataSource";
    private static final String DESC_HIKARI_PROXY = "com.zaxxer.hikari.proxy.HikariProxyConnection";

    private final MetricRegistry registry;

    public HikariPoolInstrumentation(MetricRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "hikari-pool";
    }

    @Override
    public ElementMatcher.Junction<TypeDescription> typeMatcher() {
        return named(DESC_HIKARI_DS).or(named(DESC_HIKARI_PROXY));
    }

    @Override
    public AgentBuilder apply(AgentBuilder builder) {
        if (!AgentConfig.isSqlEnabled() || !AgentConfig.isOtelJdbcEnabled()) {
            return builder;
        }
        HikariPoolProbe.bind(registry);
        return builder
                .type(named(DESC_HIKARI_DS))
                .transform((b, td, cl, m, pd) ->
                        b.visit(Advice.to(HikariPoolCtorAdvice.class).on(isConstructor())))
                .type(declaresMethod(named("getConnection").and(takesArguments(0)))
                        .and(hasSuperType(named(DESC_HIKARI_DS))))
                .transform((b, td, cl, m, pd) ->
                        b.visit(Advice.to(HikariPoolOpenAdvice.class).on(named("getConnection").and(takesArguments(0)))))
                .type(named(DESC_HIKARI_PROXY))
                .transform((b, td, cl, m, pd) ->
                        b.visit(Advice.to(HikariPoolCloseAdvice.class).on(named("close").and(takesArguments(0)))));
    }
}
