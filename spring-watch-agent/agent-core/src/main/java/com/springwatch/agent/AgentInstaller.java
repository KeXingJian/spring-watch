package com.springwatch.agent;

import com.springwatch.agent.metric.Counter;
import com.springwatch.agent.metric.Labels;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;

/**
 * ByteBuddy AgentBuilder 装配(借鉴 OTel AgentInstaller 配置风格)。
 * <p>
 * 关键配置:
 * <ul>
 *   <li>MethodGraph.Compiler.ForDeclaredMethods:不遍历类层次,启动更快</li>
 *   <li>RETRANSFORMATION:支持运行期已加载类的再织入</li>
 *   <li>POOL_ONLY:常量池最小化,减小 jar 大小</li>
 * </ul>
 */
public final class AgentInstaller {

    private static final Logger LOG = LoggerFactory.getLogger(AgentInstaller.class);

    private AgentInstaller() {
    }

    public static AgentBuilder newAgentBuilder() {
        return new AgentBuilder.Default()
                .with(new ByteBuddy()
                        .with(MethodGraph.Compiler.ForDeclaredMethods.INSTANCE)
                        .with(TypeValidation.DISABLED))
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
                .with(new AgentBuilder.InstallationListener.Adapter() {
                    public void onError(String typeName, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded, Throwable throwable) {
                        try {
                            Counter c = AppContext.get().metrics.counter(
                                    "sw_instrumentation_failures_total",
                                    "Instrumentation failures total");
                            String errType = throwable == null ? "unknown" : throwable.getClass().getSimpleName();
                            c.inc(Labels.of("type", typeName, "error_type", errType));
                        } catch (Throwable ignore) {
                        }
                        System.err.println("[kxj-diag] instrumentation error type=" + typeName
                                + " loaded=" + loaded + " err=" + throwable);
                    }
                })
                .with(new AgentBuilder.Listener.Adapter() {
                    public void onDiscovery(String typeName, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded) {
                        System.err.println("[kxj-diag] discovery type=" + typeName + " loader=" + classLoader + " loaded=" + loaded);
                    }

                    public void onError(String typeName, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded, Throwable throwable) {
                        System.err.println("[kxj-diag] transform error type=" + typeName + " loaded=" + loaded + " err=" + throwable);
                    }
                });
    }

    public static void install(Instrumentation inst) {
        AgentBuilder builder = AppContext.get().applyTo(newAgentBuilder());
        builder.installOn(inst);
    }
}
