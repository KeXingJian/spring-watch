package com.springwatch.agent.metric;

import com.springwatch.agent.config.AgentConfig;
import com.sun.management.OperatingSystemMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

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

    private static final Logger LOG = LoggerFactory.getLogger(JvmMetricsProvider.class);

    private static final String NS = "sw_jvm_";

    private static final double[] GC_BOUNDS_SEC = {
            0.001d, 0.005d, 0.01d, 0.025d, 0.05d, 0.1d, 0.25d, 0.5d, 1d, 2.5d, 5d, 10d
    };

    private final MetricRegistry registry;
    private final MemoryMXBean memory;
    private final ThreadMXBean threads;
    private final RuntimeMXBean runtime;
    private final java.lang.management.OperatingSystemMXBean osJdk;
    private final OperatingSystemMXBean osSun;
    private final ConcurrentHashMap<String, GcState> gcStates = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService gcScheduler;

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
        registerInfoMetrics();
        startGcObserver();
    }

    private void registerMemoryMetrics() {
        Gauge used = registry.gauge("jvm_memory_used_bytes", "Used bytes of a JVM memory area.");
        Gauge committed = registry.gauge("jvm_memory_committed_bytes", "Committed bytes of a JVM memory area.");
        Gauge limit = registry.gauge("jvm_memory_limit_bytes", "Max bytes of a JVM memory area (-1 if undefined).");
        Gauge maxGauge = registry.gauge("jvm_memory_max_bytes", "Max bytes of a JVM memory area (-1 if undefined).");

        registerMemoryCells(used, committed, limit, maxGauge);
        registerAfterLastGc();
    }

    private void registerMemoryCells(Gauge used, Gauge committed, Gauge limit, Gauge maxGauge) {
        used.register(Labels.of("jvm_memory_type", "heap"), () -> memory.getHeapMemoryUsage().getUsed());
        committed.register(Labels.of("jvm_memory_type", "heap"), () -> memory.getHeapMemoryUsage().getCommitted());
        limit.register(Labels.of("jvm_memory_type", "heap"), () -> maxOf(memory.getHeapMemoryUsage().getMax()));
        maxGauge.register(Labels.of("jvm_memory_type", "heap"), () -> maxOf(memory.getHeapMemoryUsage().getMax()));

        used.register(Labels.of("jvm_memory_type", "non_heap"), () -> memory.getNonHeapMemoryUsage().getUsed());
        committed.register(Labels.of("jvm_memory_type", "non_heap"), () -> memory.getNonHeapMemoryUsage().getCommitted());
        long nhMax = memory.getNonHeapMemoryUsage().getMax();
        limit.register(Labels.of("jvm_memory_type", "non_heap"), () -> maxOf(nhMax));
        maxGauge.register(Labels.of("jvm_memory_type", "non_heap"), () -> maxOf(nhMax));

        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String poolName = pool.getName();
            if (poolName == null || poolName.isBlank()) continue;
            MemoryUsage u = pool.getUsage();
            if (u == null) continue;
            String type = pool.getType() == MemoryType.HEAP ? "heap" : "non_heap";
            Labels key = Labels.of("jvm_memory_type", type, "jvm_memory_pool_name", poolName);

            used.register(key, u::getUsed);
            committed.register(key, u::getCommitted);
            long pMax = u.getMax();
            limit.register(key, () -> maxOf(pMax));
            maxGauge.register(key, () -> maxOf(pMax));
        }
    }

    private static double maxOf(long v) {
        return v < 0 ? -1d : (double) v;
    }

    /**
     * 各内存池上一次 GC 后的已用字节数(OTel {@code jvm.memory.used_after_last_gc})。
     * 仅支持 {@code collectionUsage} 的池注册(如 G1 heap 池),其余自动跳过。
     */
    private void registerAfterLastGc() {
        Gauge afterGc = registry.gauge("jvm_memory_used_after_last_gc_bytes",
                "Memory used by a JVM memory pool after the last GC.");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String poolName = pool.getName();
            if (poolName == null || poolName.isBlank()) continue;
            MemoryUsage u = pool.getCollectionUsage();
            if (u == null) continue;
            String type = pool.getType() == MemoryType.HEAP ? "heap" : "non_heap";
            afterGc.register(Labels.of("jvm_memory_pool_name", poolName, "jvm_memory_type", type), u::getUsed);
        }
    }

    private void registerInfoMetrics() {
        Gauge info = registry.gauge("jvm_info", "JVM version and vendor information.");
        info.register(Labels.of(
                new String[]{"java_version", "java_vendor", "runtime_name", "runtime_version"},
                new String[]{
                        System.getProperty("java.version", "unknown"),
                        System.getProperty("java.vendor", "unknown"),
                        System.getProperty("java.runtime.name", "unknown"),
                        System.getProperty("java.runtime.version", "unknown")}), () -> 1d);
    }

    private void registerGcMetrics() {
        registry.histogram("jvm_gc_duration_seconds",
                "GC pause duration distribution in seconds.", GC_BOUNDS_SEC);
        registry.gauge("jvm_gc_memory_allocated_bytes_total",
                "Total memory allocated by the JVM after each GC event, per pool (cumulative).");
        registry.gauge("jvm_gc_memory_promoted_bytes_total",
                "Total memory promoted to old generation after each GC event, per pool (cumulative).");
    }

    /**
     * GC 增量观察器:每 5s 读取各收集器的累计 count/time,把增量按均值喂进
     * {@code jvm_gc_duration_seconds} 直方图(供平台分位面板),同时用
     * {@code GcInfo.getId()} 去重累计 allocated / promoted 字节数。
     * <p>
     * MXBean 只有累计值、无逐次停顿明细,均值近似是零依赖下的最佳口径;
     * count/sum 与旧 gauge 完全一致,前端 grouped 查询零回归。
     */
    private void startGcObserver() {
        if (gcScheduler != null) return;
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sw-jvm-gc-observe");
            t.setDaemon(true);
            return t;
        });
        this.gcScheduler = s;
        s.scheduleWithFixedDelay(this::observeGc, 5, 5, TimeUnit.SECONDS);
        LOG.info("[kxj: JVM GC 观察器启动 - interval=5s - jvm_gc_duration_seconds 分位直方图 + gc_memory_allocated/promoted]");
    }

    private void observeGc() {
        try {
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                String name = safeGcName(bean.getName());
                GcState st = gcStates.computeIfAbsent(name, k -> new GcState());

                long count = bean.getCollectionCount();
                long timeMs = bean.getCollectionTime();
                long dCount = count - st.lastCount;
                long dTimeMs = timeMs - st.lastTimeMillis;
                if (dCount > 0 && dCount < 100_000L) {
                    double avgSec = dTimeMs / 1000.0 / dCount;
                    Histogram hist = registry.histograms().get("jvm_gc_duration_seconds");
                    if (hist != null) {
                        Labels key = Labels.of("jvm_gc_name", name, "jvm_gc_action", inferGcAction(bean.getName()));
                        for (long i = 0; i < dCount; i++) {
                            hist.observe(key, avgSec);
                        }
                    }
                    st.lastCount = count;
                    st.lastTimeMillis = timeMs;
                }
                accumulateGcMemory(st, bean, name);
            }
        } catch (Throwable t) {
            LOG.debug("[kxj: JVM GC 观察异常 - error={}]", t.getMessage());
        }
    }

    private void accumulateGcMemory(GcState st, GarbageCollectorMXBean bean, String gcName) {
        if (!(bean instanceof com.sun.management.GarbageCollectorMXBean sunBean)) return;
        com.sun.management.GcInfo gi = sunBean.getLastGcInfo();
        if (gi == null) return;
        long id = gi.getId();
        if (id == st.lastGcInfoId) return;
        st.lastGcInfoId = id;
        Map<String, MemoryUsage> before = gi.getMemoryUsageBeforeGc();
        Map<String, MemoryUsage> after = gi.getMemoryUsageAfterGc();
        if (before == null || after == null) return;
        for (Map.Entry<String, MemoryUsage> e : after.entrySet()) {
            String pool = e.getKey();
            MemoryUsage uBefore = before.get(pool);
            MemoryUsage uAfter = e.getValue();
            if (uBefore == null || uAfter == null) continue;
            long delta = uAfter.getUsed() - uBefore.getUsed();
            if (delta > 0) {
                LongAdder adder = st.allocated.computeIfAbsent(pool, k -> new LongAdder());
                adder.add(delta);
                registerGcMemoryCell("jvm_gc_memory_allocated_bytes_total", gcName, pool, adder);
            } else if (delta < 0) {
                LongAdder adder = st.promoted.computeIfAbsent(pool, k -> new LongAdder());
                adder.add(-delta);
                registerGcMemoryCell("jvm_gc_memory_promoted_bytes_total", gcName, pool, adder);
            }
        }
    }

    private void registerGcMemoryCell(String metric, String gcName, String pool, LongAdder adder) {
        Gauge g = registry.gauges().get(metric);
        if (g == null) return;
        g.register(Labels.of("jvm_gc_name", gcName, "jvm_memory_pool_name", pool), adder::sum);
    }

    private static final class GcState {
        long lastCount;
        long lastTimeMillis;
        long lastGcInfoId = -1L;
        final Map<String, LongAdder> allocated = new ConcurrentHashMap<>();
        final Map<String, LongAdder> promoted = new ConcurrentHashMap<>();
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
