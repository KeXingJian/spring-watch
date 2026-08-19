package com.springwatch.agent.metric;

import com.springwatch.agent.config.AgentConfig;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;

/**
 * JVM / 系统级指标(零依赖,纯 JDK API)。
 * <p>
 * 双命名空间并存(向前兼容):
 * <ul>
 *   <li>{@code sw_jvm_*}:老版命名,沿用最早的白皮书口径;</li>
 *   <li>{@code jvm_*}:对齐 OpenTelemetry semantic conventions + 平台前端
 *       {@code useAppView.ts: jvmViewSpecs()} 的查询口径,实现前端
 *       JvmPane 直接出图。</li>
 * </ul>
 * 关闭 jvm 能力时两类一起跳过。
 */
public final class JvmMetricsProvider {

    private static final String NS = "sw_jvm_";

    private final MetricRegistry registry;
    private final MemoryMXBean memory;
    private final ThreadMXBean threads;
    private final RuntimeMXBean runtime;
    private final java.lang.management.OperatingSystemMXBean osJdk;
    private final OperatingSystemMXBean osSun;

    public JvmMetricsProvider(MetricRegistry registry) {
        this.registry = registry;
        this.memory = ManagementFactory.getMemoryMXBean();
        this.threads = ManagementFactory.getThreadMXBean();
        this.runtime = ManagementFactory.getRuntimeMXBean();
        this.osJdk = ManagementFactory.getOperatingSystemMXBean();
        this.osSun = (osJdk instanceof OperatingSystemMXBean) ? (OperatingSystemMXBean) osJdk : null;
    }

    public void register() {
        if (!AgentConfig.isJvmEnabled()) return;

        registerSwNamespace();

        if (AgentConfig.isOtelJvmEnabled()) {
            registerOtelNamespace();
        }
    }

    /**
     * 老 {@code sw_jvm_*} 命名空间,与 v1 版本行为一致,不动。
     */
    private void registerSwNamespace() {
        Gauge heapUsed = registry.gauge(NS + "heap_bytes", "Used heap bytes");
        heapUsed.register(Labels.of("area", "used"), () -> memory.getHeapMemoryUsage().getUsed());
        Gauge heapCommitted = registry.gauge(NS + "heap_bytes", "Committed heap bytes");
        heapCommitted.register(Labels.of("area", "committed"), () -> memory.getHeapMemoryUsage().getCommitted());
        Gauge heapMax = registry.gauge(NS + "heap_bytes", "Max heap bytes");
        heapMax.register(Labels.of("area", "max"), () -> memory.getHeapMemoryUsage().getMax());

        Gauge nonHeapUsed = registry.gauge(NS + "nonheap_bytes", "Used non-heap bytes");
        nonHeapUsed.register(Labels.of("area", "used"), () -> memory.getNonHeapMemoryUsage().getUsed());

        Gauge threadCount = registry.gauge(NS + "thread_count", "Live thread count");
        threadCount.register(Labels.EMPTY, () -> threads.getThreadCount());
        Gauge threadPeak = registry.gauge(NS + "thread_peak", "Peak thread count");
        threadPeak.register(Labels.EMPTY, () -> threads.getPeakThreadCount());
        Gauge threadDaemon = registry.gauge(NS + "thread_daemon_count", "Daemon thread count");
        threadDaemon.register(Labels.EMPTY, () -> threads.getDaemonThreadCount());

        Gauge cpuLoad = registry.gauge(NS + "cpu_load", "Process CPU load");
        if (osSun != null) {
            cpuLoad.register(Labels.EMPTY, () -> osSun.getProcessCpuLoad());
        }

        gaugeProcessUptime();

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            Gauge count = registry.gauge(NS + "gc_count", "GC count");
            count.register(Labels.of("name", gc.getName()), gc::getCollectionCount);
            Gauge time = registry.gauge(NS + "gc_time_seconds", "GC time in seconds");
            time.register(Labels.of("name", gc.getName()), () -> gc.getCollectionTime() / 1000.0);
        }
    }

    /**
     * OTel 风格 {@code jvm_*} 命名空间,对齐平台前端视图期望的字段。
     * <p>
     * 完整覆盖 frontend {@code jvmViewSpecs()} 的查询口径 + 平台 V11 metric_dict
     * 中 JvmMetrics 的所有登记名。
     */
    private void registerOtelNamespace() {
        registerMemoryMetrics();
        registerGcMetrics();
        registerThreadMetrics();
        registerClassMetrics();
        registerCpuMetrics();
        registerUptimeMetrics();
    }

    private void registerMemoryMetrics() {
        long max = memory.getHeapMemoryUsage().getMax();

        Gauge used = registry.gauge("jvm_memory_used_bytes", "Used bytes of a JVM memory area.");
        Gauge committed = registry.gauge("jvm_memory_committed_bytes", "Committed bytes of a JVM memory area.");
        Gauge limit = registry.gauge("jvm_memory_limit_bytes", "Max bytes of a JVM memory area (-1 if undefined).");

        used.register(Labels.of("jvm_memory_type", "heap"), () -> memory.getHeapMemoryUsage().getUsed());
        committed.register(Labels.of("jvm_memory_type", "heap"), () -> memory.getHeapMemoryUsage().getCommitted());
        limit.register(Labels.of("jvm_memory_type", "heap"), () -> max < 0 ? -1d : (double) max);

        used.register(Labels.of("jvm_memory_type", "non_heap"), () -> memory.getNonHeapMemoryUsage().getUsed());
        committed.register(Labels.of("jvm_memory_type", "non_heap"), () -> memory.getNonHeapMemoryUsage().getCommitted());
        long nhMax = memory.getNonHeapMemoryUsage().getMax();
        limit.register(Labels.of("jvm_memory_type", "non_heap"), () -> nhMax < 0 ? -1d : (double) nhMax);

        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String poolName = pool.getName();
            if (poolName == null || poolName.isBlank()) continue;
            MemoryUsage u = pool.getUsage();
            if (u == null) continue;
            String type = pool.getType() == MemoryType.HEAP ? "heap" : "non_heap";

            used.register(Labels.of("jvm_memory_type", type, "jvm_memory_pool_name", poolName), u::getUsed);
            committed.register(Labels.of("jvm_memory_type", type, "jvm_memory_pool_name", poolName), u::getCommitted);
            long pMax = u.getMax();
            limit.register(Labels.of("jvm_memory_type", type, "jvm_memory_pool_name", poolName),
                    () -> pMax < 0 ? -1d : (double) pMax);
        }
    }

    private void registerGcMetrics() {
        Gauge countGauge = registry.gauge("jvm_gc_duration_seconds_count",
                "Number of JVM garbage collection operations.");
        Gauge sumGauge = registry.gauge("jvm_gc_duration_seconds_sum",
                "Sum of JVM garbage collection pause durations in seconds.");

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = safeGcName(gc.getName());
            String action = inferGcAction(gc.getName());
            Labels key = Labels.of("jvm_gc_name", name, "jvm_gc_action", action);
            countGauge.register(key, gc::getCollectionCount);
            sumGauge.register(key, () -> gc.getCollectionTime() / 1000.0);
        }
    }

    private static String safeGcName(String raw) {
        return raw == null || raw.isBlank() ? "unknown" : raw;
    }

    /**
     * OTel semantic conventions 把 G1/Parallel/Serial 收集器映射成
     * {@code "end of minor GC"} 或 {@code "end of major GC"};其他返回 {@code "unknown"}。
     */
    private static String inferGcAction(String gcName) {
        if (gcName == null) return "unknown";
        String lower = gcName.toLowerCase();
        if (lower.contains("old") || lower.contains("major") || lower.contains("cms")) {
            return "end of major GC";
        }
        if (lower.contains("young") || lower.contains("minor") || lower.contains("pauseless")) {
            return "end of minor GC";
        }
        return "unknown";
    }

    private void registerThreadMetrics() {
        Gauge liveGauge = registry.gauge("jvm_threads_live_threads", "Current live JVM thread count.");
        Gauge daemonGauge = registry.gauge("jvm_threads_daemon_threads", "Current daemon JVM thread count.");
        Gauge peakGauge = registry.gauge("jvm_threads_peak_threads", "Peak JVM thread count.");

        liveGauge.register(Labels.EMPTY, () -> threads.getThreadCount());
        daemonGauge.register(Labels.EMPTY, () -> threads.getDaemonThreadCount());
        peakGauge.register(Labels.EMPTY, () -> threads.getPeakThreadCount());

        Thread.State[] states = Thread.State.values();
        Gauge stateCount = registry.gauge("jvm_threads_states",
                "JVM threads by state (new / runnable / blocked / waiting / timed_waiting / terminated).");
        for (Thread.State state : states) {
            stateCount.register(Labels.of("state", state.name().toLowerCase()), () -> countThreadsInState(state));
        }

        Gauge threadCount = registry.gauge("jvm_thread_count",
                "JVM thread count split by thread state and daemon flag.");
        for (Thread.State state : states) {
            for (boolean daemon : new boolean[]{true, false}) {
                Labels key = Labels.of("jvm_thread_state", state.name().toLowerCase(),
                        "jvm_thread_daemon", String.valueOf(daemon));
                threadCount.register(key, () -> countThreadsInStateByDaemon(state, daemon));
            }
        }
    }

    private long countThreadsInState(Thread.State state) {
        long c = 0L;
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t != null && t.getState() == state) c++;
            }
        } catch (Throwable ignore) {
        }
        return c;
    }

    private long countThreadsInStateByDaemon(Thread.State state, boolean daemon) {
        long c = 0L;
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t == null || t.getState() != state || t.isDaemon() != daemon) continue;
                c++;
            }
        } catch (Throwable ignore) {
        }
        return c;
    }

    private void registerClassMetrics() {
        java.lang.management.ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        Gauge current = registry.gauge("jvm_class_count", "Current loaded class count.");
        Gauge loaded = registry.gauge("jvm_class_loaded_total", "Total loaded class count since process start.");
        Gauge unloaded = registry.gauge("jvm_class_unloaded_total", "Total unloaded class count since process start.");

        current.register(Labels.EMPTY, () -> cl.getLoadedClassCount());
        loaded.register(Labels.EMPTY, () -> cl.getTotalLoadedClassCount());
        unloaded.register(Labels.EMPTY, () -> cl.getUnloadedClassCount());
    }

    private void registerCpuMetrics() {
        if (osSun == null) return;
        Gauge recent = registry.gauge("jvm_cpu_recent_utilization",
                "Recent JVM process CPU utilization in [0,1].");
        recent.register(Labels.EMPTY, () -> osSun.getProcessCpuLoad());

        Gauge total = registry.gauge("jvm_cpu_time_seconds_total",
                "Total CPU time consumed by the JVM process in seconds.");
        total.register(Labels.EMPTY, () -> osSun.getProcessCpuTime() / 1_000_000_000.0);

        Gauge count = registry.gauge("jvm_cpu_count", "Number of CPUs available to the JVM process.");
        count.register(Labels.EMPTY, osSun::getAvailableProcessors);
    }

    private void registerUptimeMetrics() {
        Gauge uptime = registry.gauge("jvm_uptime_seconds", "JVM process uptime in seconds.");
        uptime.register(Labels.EMPTY, () -> (System.currentTimeMillis() - runtime.getStartTime()) / 1000.0);
    }

    private void gaugeProcessUptime() {
        Gauge uptime = registry.gauge(NS + "uptime_seconds", "Process uptime in seconds");
        uptime.register(Labels.EMPTY, () -> (System.currentTimeMillis() - runtime.getStartTime()) / 1000.0);
    }

    public MemoryUsage heapMemoryUsage() {
        return memory.getHeapMemoryUsage();
    }

    public java.lang.management.OperatingSystemMXBean os() {
        return osJdk;
    }
}
