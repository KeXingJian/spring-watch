package com.springwatch.agent.instrument.web;

import com.springwatch.agent.metric.Histogram;
import com.springwatch.agent.metric.Labels;
import com.springwatch.agent.metric.MetricRegistry;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DispatcherServlet.doDispatch 拦截 Advice。
 * <p>
 * 设计要点 — 与 app 级 classloader 类型零耦合:
 * <ul>
 *   <li>{@code @Advice.Argument} 全用 {@code Object},不 import {@code HttpServletRequest/Response},
 *       这样 Advice 类加载于 bootstrap classloader 时不会触发 servlet 类型解析;
 *       不存在 spring-web 的客户环境反而不会受影响(无 dispatcher 可匹配)。</li>
 *   <li>{@link #lookup} 把目标对象的反射方法按 {@code (class, name, paramTypes)} 缓存到
 *       {@link ConcurrentHashMap};首次调用后稳态零反射,JIT 后接近直接调用开销。</li>
 *   <li>URI 模板取自 Spring MVC 标准属性 {@code org.springframework.web.servlet.HandlerMapping.bestMatchingPattern};
 *       未设置(未匹配 controller)时用 {@code "UNKNOWN"} 收敛基数。</li>
 * </ul>
 */
public final class DispatcherAdvice {

    private static final String ATTR_BEST_PATTERN =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    static final double[] BOUNDS_SEC = {
            0.005d, 0.01d, 0.025d, 0.05d, 0.1d, 0.25d, 0.5d, 1d, 2.5d, 5d, 10d
    };

    private static final ConcurrentHashMap<MethodKey, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private DispatcherAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long onEnter() {
        HttpServerInstrumentation.ActiveRequests.INSTANCE.inc();
        return System.nanoTime();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(@Advice.Argument(0) Object request,
                              @Advice.Argument(1) Object response,
                              @Advice.Enter long startNanos,
                              @Advice.Thrown Throwable thrown) {
        try {
            HttpServerInstrumentation.ActiveRequests.INSTANCE.dec();

            long durationNanos = System.nanoTime() - startNanos;
            if (durationNanos < 0L) durationNanos = 0L;
            double durationSec = durationNanos / 1_000_000_000.0;

            String method = invokeString(request, "getMethod");
            if (method == null) method = "UNKNOWN";
            String route = invokeStringAttr(request, ATTR_BEST_PATTERN);
            if (route == null) route = "UNKNOWN";
            int status = invokeInt(response, "getStatus");
            if (status <= 0) status = 200;

            Histogram hist = HttpHistogramHolder.get();
            if (hist == null) return;

            Labels labels = Labels.of(
                    "http_request_method", method,
                    "http_route", route,
                    "http_response_status_code", String.valueOf(status));
            hist.observe(labels, durationSec);
        } catch (Throwable ignore) {
        }
    }

    private static String invokeString(Object target, String name) {
        try {
            Method m = lookup(target, name);
            if (m == null) return null;
            Object v = m.invoke(target);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String invokeStringAttr(Object target, String attr) {
        try {
            Method m = lookup(target, "getAttribute", String.class);
            if (m == null) return null;
            Object v = m.invoke(target, attr);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int invokeInt(Object target, String name) {
        try {
            Method m = lookup(target, name);
            if (m == null) return 0;
            Object v = m.invoke(target);
            return v instanceof Number ? ((Number) v).intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Method lookup(Object target, String name, Class<?>... paramTypes) {
        MethodKey k = new MethodKey(target.getClass(), name, paramTypes);
        Method m = METHOD_CACHE.get(k);
        if (m != null) return m;
        try {
            Method found = target.getClass().getMethod(name, paramTypes);
            METHOD_CACHE.put(k, found);
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    private record MethodKey(Class<?> klass, String name, Class<?>[] paramTypes) {}

    /**
     * 注册一次,所有线程访问同一个 Histogram 实例。
     */
    static final class HttpHistogramHolder {
        private static volatile Histogram INSTANCE;

        static Histogram get() {
            return INSTANCE;
        }

        static synchronized void bind(MetricRegistry registry, String name, String help, double[] bounds) {
            if (INSTANCE != null) return;
            INSTANCE = registry.histogram(name, help, bounds);
        }
    }
}
