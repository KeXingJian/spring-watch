package com.springwatch.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

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

    private static final String BOOT_PACKAGE_PATH = "com/springwatch/agent/boot/";

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

    /**
     * 只将 boot 包({@code com.springwatch.agent.boot.*},即 JdbcStorage)追加到 bootstrap classloader。
     * <p>
     * 不能追加整个 fat jar:否则 agent 其余类被 bootstrap 抢先加载,而 bootstrap 无
     * {@code org.slf4j.LoggerFactory},会抛 NoClassDefFoundError。boot 包保持零依赖
     * (仅 JDK 类型),追加后 P2 的 java.sql advice 才能引用到 JdbcStorage。
     */
    private static void injectBootstrap(Instrumentation inst) {
        File agentJar = locateAgentJar();
        if (agentJar == null) {
            LOG.warn("[kxj: 无法定位 agent jar - bootstrap 注入跳过]");
            return;
        }
        File bootJar = extractBootJar(agentJar);
        if (bootJar == null) {
            LOG.warn("[kxj: 提取 boot jar 失败 - bootstrap 注入跳过]");
            return;
        }
        try (JarFile jar = new JarFile(bootJar)) {
            inst.appendToBootstrapClassLoaderSearch(jar);
            LOG.info("[kxj: bootstrap classloader 已注入 - jar={}]", bootJar.getName());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static File extractBootJar(File agentJar) {
        try {
            File tmp = File.createTempFile("spring-watch-agent-boot-", ".jar");
            tmp.deleteOnExit();
            try (JarFile src = new JarFile(agentJar);
                 JarOutputStream out = new JarOutputStream(new FileOutputStream(tmp))) {
                Enumeration<JarEntry> entries = src.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    String name = e.getName();
                    if (!name.startsWith(BOOT_PACKAGE_PATH) || e.isDirectory()) {
                        continue;
                    }
                    try (InputStream in = src.getInputStream(e)) {
                        out.putNextEntry(new JarEntry(name));
                        in.transferTo(out);
                        out.closeEntry();
                    }
                }
            }
            return tmp;
        } catch (IOException e) {
            LOG.warn("[kxj: 提取 boot jar 异常 - error={}]", e.getMessage());
            return null;
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
