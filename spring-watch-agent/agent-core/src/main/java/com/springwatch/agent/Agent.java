package com.springwatch.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.jar.JarFile;

/**
 * Java Agent 入口。
 * <p>
 * 加载顺序:
 * <ol>
 *   <li>premain 立即将自身 jar 注入 bootstrap classloader(为 P2 java.sql 层提供 JdbcStorage)</li>
 *   <li>初始化 AppContext(开 HTTP + 注册 logback + 启动 JdbcEventExporter)</li>
 *   <li>挂 ByteBuddy ClassFileTransformer,后续类加载时被织入</li>
 *   <li>premain 退出后 JVM 继续启动业务</li>
 * </ol>
 *
 * <p>启动参数:
 * <pre>
 *   -javaagent:/path/to/spring-watch-agent.jar
 *   -Dspring.watch.metrics.port=9464
 *   -Dspring.watch.log.buffer.size=65536
 *   -Dspring.watch.app.name=order-service
 *   -Dspring.watch.token=xxx      (可选,开启后 /api/agent/logs 需 Authorization)
 *   -Dspring.watch.disable=sql    (可选,逗号分隔: logs/method/sql/jvm)
 * </pre>
 */
public final class Agent {

    private static final Logger LOG = LoggerFactory.getLogger(Agent.class);

    private Agent() {
    }

    public static void premain(String args, Instrumentation inst) {
        try {
            injectBootstrap(inst);
        } catch (Throwable t) {
            LOG.warn("[kxj: bootstrap 注入失败 - P2 JDBC 不可用 - error={}]", t.getMessage());
        }
        try {
            AppContext.init();
            AgentInstaller.install(inst);
            LOG.info("[kxj: Agent 启动成功 - version={}]", AppContext.VERSION);
        } catch (Throwable t) {
            LOG.warn("[kxj: Agent 启动失败 - 业务不受影响 - error={}]", t.getMessage());
        }
    }

    private static void injectBootstrap(Instrumentation inst) {
        File agentJar = locateAgentJar();
        if (agentJar == null) {
            LOG.warn("[kxj: 无法定位 agent jar - bootstrap 注入跳过]");
            return;
        }
        try (JarFile jar = new JarFile(agentJar)) {
            inst.appendToBootstrapClassLoaderSearch(jar);
            LOG.info("[kxj: bootstrap classloader 已注入 - jar={}]", agentJar.getName());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static File locateAgentJar() {
        try {
            CodeSource cs = Agent.class.getProtectionDomain().getCodeSource();
            if (cs == null) return null;
            URL loc = cs.getLocation();
            if (loc == null) return null;
            File f = new File(loc.toURI());
            if (f.isFile() && f.getName().endsWith(".jar")) {
                return f;
            }
            return null;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
