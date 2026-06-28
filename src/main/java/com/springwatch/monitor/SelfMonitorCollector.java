package com.springwatch.monitor;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import com.springwatch.collector.KafkaFallbackQueue;
import com.springwatch.collector.schedule.HostThrottler;
import com.sun.management.OperatingSystemMXBean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自监控采集器 - 定时采样 JVM/进程/系统/Micrometer 指标。
 * P1-6: 缩小 RING_SIZE 至 60（10min 历史）；引入 Meter 白名单，避免高基数 Gauge 撑爆堆。
 *
 * 持久化（与 BatchMetricConsumer 保持同款 InfluxDB 写入路径）：
 * - 每次采样落 InfluxDB self_metrics 桶，measurement=self_monitor_metrics
     * - tags: appid=self, category=jvm|process|meter, metric=<扁平名>, meter_type=counter|gauge|timer, gc_name=<仅 GC>, pool_name=<仅内存池>
 * - field: value（double）；timer 还会带 count/total_ms/max_ms
 * - 内存 ring 仅供 /realtime（5s 轮询）做卡片/列表的快速缓存；1h/6h/24h 历史曲线统一走 SelfMetricQueryService 查 InfluxDB
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfMonitorCollector {

    private static final long SAMPLE_INTERVAL_SEC = 10L;
    private static final int RING_SIZE = 60;
    private static final String METER_PREFIX = "spring.watch.";

    /** InfluxDB: measurement / appid 标记，方便查询侧复用 appid 过滤模式 */
    private static final String MEASUREMENT = "self_monitor_metrics";
    private static final String APPID_SELF = "self";
    private static final String CAT_JVM = "jvm";
    private static final String CAT_PROCESS = "process";
    private static final String CAT_METER = "meter";

    /**
     * 允许采集的 meter 名前缀白名单。前缀未匹配则跳过，避免高基数 Gauge（如 http_server_requests × appid）
     * 撑爆 ring。
     */
    private static final Set<String> METER_WHITELIST_PREFIXES = Set.of(
            "spring.watch.http.",
            "spring.watch.collector.http.",
            "spring.watch.collector.kafka.",
            "spring.watch.consumer.",
            "spring.watch.kafka.",
            "spring.watch.jvm.",
            "spring.watch.system.",
            "spring.watch.process.",
            "spring.watch.alert.",
            "spring.watch.log.",
            "spring.watch.metric.",
            "spring.watch.ingest."
    );

    private final MeterRegistry meterRegistry;
    private final KafkaFallbackQueue kafkaFallbackQueue;
    private final HostThrottler hostThrottler;
    private final WriteApi writeApi;
    private final WriteParameters selfMetricsWriteParameters;

    private ScheduledExecutorService scheduler;
    private final Deque<Sample> ring = new ArrayDeque<>(RING_SIZE);
    private final Object lock = new Object();

    /** 增量更新：保留每个 meter 上次采样值，避免每次全量遍历拷贝。 */
    private final Map<String, Double> lastCounterValues = new ConcurrentHashMap<>();
    private final Map<String, TimerSnap> lastTimerSnaps = new ConcurrentHashMap<>();
    private final Map<String, Double> lastGaugeValues = new ConcurrentHashMap<>();
    private Counter filteredMeterCounter;
    private Counter capturedMeterCounter;
    private Counter persistedCounter;
    private Counter persistFailCounter;

    @PostConstruct
    void start() {
        this.filteredMeterCounter = Counter.builder("spring.watch.self.monitor.capture.filtered")
                .description("被白名单过滤掉的 meter 数量")
                .register(meterRegistry);
        this.capturedMeterCounter = Counter.builder("spring.watch.self.monitor.capture.captured")
                .description("实际采集的 meter 数量")
                .register(meterRegistry);
        this.persistedCounter = Counter.builder("spring.watch.self.monitor.persist.ok")
                .description("自监控指标写入 InfluxDB 成功条数")
                .register(meterRegistry);
        this.persistFailCounter = Counter.builder("spring.watch.self.monitor.persist.fail")
                .description("自监控指标写入 InfluxDB 失败次数")
                .register(meterRegistry);
        Gauge.builder("spring.watch.collector.kafka.fallback.size", kafkaFallbackQueue, KafkaFallbackQueue::size)
                .description("Kafka 兜底队列当前堆积")
                .register(meterRegistry);
        Gauge.builder("spring.watch.collector.host_throttler.active", hostThrottler, h -> (double) h.activeHosts())
                .description("已注册主机限流器数量")
                .register(meterRegistry);
        Gauge.builder("spring.watch.self.monitor.ring.size", this, s -> (double) s.size())
                .description("自监控 ring 当前样本数")
                .register(meterRegistry);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "self-monitor-sampler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::sample, 0L, SAMPLE_INTERVAL_SEC, TimeUnit.SECONDS);
        log.info("[spring-watch: SelfMonitorCollector 启动 - interval={}s, ring={}, whitelist={}]",
                SAMPLE_INTERVAL_SEC, RING_SIZE, METER_WHITELIST_PREFIXES.size());
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        log.info("[spring-watch: SelfMonitorCollector 关闭 - samples={}]", ring.size());
    }

    private void sample() {
        try {
            Sample s = build();
            synchronized (lock) {
                if (ring.size() >= RING_SIZE) ring.pollFirst();
                ring.addLast(s);
            }
            persist(s);
        } catch (Throwable t) {
            log.warn("[spring-watch: SelfMonitorCollector 采样异常 - error={}]", t.getMessage());
        }
    }

    /**
     * 把一次 sample 扁平化后批量写入 InfluxDB self_metrics 桶。
     * 与 BatchMetricConsumer 走同一个 WriteApi（共享写缓冲/重试/限速），失败仅打点不抛。
     */
    private void persist(Sample s) {
        List<Point> points;
        try {
            points = toPoints(s);
        } catch (Throwable t) {
            persistFailCounter.increment();
            log.warn("[spring-watch: SelfMonitorCollector 转换InfluxDB Point失败 - error={}]", t.getMessage());
            return;
        }
        if (points.isEmpty()) return;
        try {
            writeApi.writePoints(points, selfMetricsWriteParameters);
            persistedCounter.increment(points.size());
        } catch (Throwable t) {
            persistFailCounter.increment();
            log.warn("[spring-watch: SelfMonitorCollector 写InfluxDB失败 - size={}, error={}]",
                    points.size(), t.getMessage());
        }
    }

    /**
     * 把 Sample 拆成扁平 InfluxDB Point 列表：
     * - jvm 段 → category=jvm, metric=heap.used / nonHeap.used / pool.used(pool_name) / gc.count(gc_name) / gc.time_ms(gc_name) ...
     * - process 段 → category=process, metric=cpu_load / rss_bytes / ...
     * - meters 段 → category=meter, meter_type=counter|gauge|timer, metric=<原始 meter 名>
     *
     * 这样查询时与 MetricQueryService.querySeries 走同一套 Flux 模板（measurement + appid + metric + _field=value），
     * 减少新增查询路径的心智负担。
     */
    private List<Point> toPoints(Sample s) {
        long tsNs = s.ts * 1_000_000L;
        List<Point> out = new ArrayList<>(64);

        JvmSnap jvm = s.jvm;
        if (jvm != null) {
            if (jvm.heap() != null) {
                add(out, tsNs, CAT_JVM, "heap.used", jvm.heap().used());
                add(out, tsNs, CAT_JVM, "heap.committed", jvm.heap().committed());
                add(out, tsNs, CAT_JVM, "heap.max", jvm.heap().max());
                add(out, tsNs, CAT_JVM, "heap.init", jvm.heap().init());
            }
            if (jvm.nonHeap() != null) {
                add(out, tsNs, CAT_JVM, "nonHeap.used", jvm.nonHeap().used());
                add(out, tsNs, CAT_JVM, "nonHeap.committed", jvm.nonHeap().committed());
                add(out, tsNs, CAT_JVM, "nonHeap.max", jvm.nonHeap().max());
                add(out, tsNs, CAT_JVM, "nonHeap.init", jvm.nonHeap().init());
            }
            if (jvm.pools() != null) {
                for (PoolSnap ps : jvm.pools()) {
                    if (ps == null || ps.name() == null || ps.name().isBlank()) continue;
                    Map<String, String> tags = Map.of("pool_name", ps.name());
                    if (ps.mem() != null) {
                        add(out, tsNs, CAT_JVM, "pool.used", ps.mem().used(), tags);
                        add(out, tsNs, CAT_JVM, "pool.committed", ps.mem().committed(), tags);
                        add(out, tsNs, CAT_JVM, "pool.max", ps.mem().max(), tags);
                        add(out, tsNs, CAT_JVM, "pool.init", ps.mem().init(), tags);
                        // 兼容旧 jvmMemChart 上的 "Metaspace MB" 折线
                        if ("Metaspace".equalsIgnoreCase(ps.name())) {
                            add(out, tsNs, CAT_JVM, "metaspace.used", ps.mem().used());
                            add(out, tsNs, CAT_JVM, "metaspace.committed", ps.mem().committed());
                        }
                    }
                }
            }
            if (jvm.threads() != null) {
                add(out, tsNs, CAT_JVM, "threads.current", jvm.threads().current());
                add(out, tsNs, CAT_JVM, "threads.daemon", jvm.threads().daemon());
                add(out, tsNs, CAT_JVM, "threads.peak", jvm.threads().peak());
                add(out, tsNs, CAT_JVM, "threads.deadlocked", jvm.threads().deadlocked());
            }
            if (jvm.classes() != null) {
                add(out, tsNs, CAT_JVM, "classes.loaded", jvm.classes().loaded());
                add(out, tsNs, CAT_JVM, "classes.totalLoaded", jvm.classes().totalLoaded());
                add(out, tsNs, CAT_JVM, "classes.unloaded", jvm.classes().unloaded());
            }
            add(out, tsNs, CAT_JVM, "uptime_ms", jvm.uptimeMs());
            if (jvm.gc() != null) {
                for (GcSnap g : jvm.gc()) {
                    if (g == null || g.name() == null) continue;
                    add(out, tsNs, CAT_JVM, "gc.count", g.count(), Map.of("gc_name", g.name()));
                    add(out, tsNs, CAT_JVM, "gc.time_ms", g.timeMs(), Map.of("gc_name", g.name()));
                }
            }
        }

        ProcessSnap proc = s.process;
        if (proc != null) {
            add(out, tsNs, CAT_PROCESS, "cpu_load", proc.cpuLoad());
            add(out, tsNs, CAT_PROCESS, "system_cpu_load", proc.systemCpuLoad());
            add(out, tsNs, CAT_PROCESS, "rss_bytes", proc.rssBytes());
            add(out, tsNs, CAT_PROCESS, "virtual_bytes", proc.virtualBytes());
            add(out, tsNs, CAT_PROCESS, "heap_used", proc.heapUsed());
            add(out, tsNs, CAT_PROCESS, "non_heap_used", proc.nonHeapUsed());
            add(out, tsNs, CAT_PROCESS, "system_total_bytes", proc.systemTotalBytes());
            add(out, tsNs, CAT_PROCESS, "system_free_bytes", proc.systemFreeBytes());
            add(out, tsNs, CAT_PROCESS, "disk_free_bytes", proc.diskFreeBytes());
            add(out, tsNs, CAT_PROCESS, "cpu_cores", proc.cpuCores());
        }

        MeterSnap meters = s.meters;
        if (meters != null) {
            if (meters.counters() != null) {
                for (Map.Entry<String, Double> e : meters.counters().entrySet()) {
                    add(out, tsNs, CAT_METER, e.getKey(), e.getValue(), Map.of("meter_type", "counter"));
                }
            }
            if (meters.gauges() != null) {
                for (Map.Entry<String, Double> e : meters.gauges().entrySet()) {
                    add(out, tsNs, CAT_METER, e.getKey(), e.getValue(), Map.of("meter_type", "gauge"));
                }
            }
            if (meters.timers() != null) {
                for (Map.Entry<String, TimerSnap> e : meters.timers().entrySet()) {
                    TimerSnap t = e.getValue();
                    if (t == null) continue;
                    Point p = Point.measurement(MEASUREMENT)
                            .addTag("appid", APPID_SELF)
                            .addTag("category", CAT_METER)
                            .addTag("meter_type", "timer")
                            .addTag("metric", e.getKey())
                            .addField("count", t.count())
                            .addField("total_ms", t.totalMs())
                            .addField("max_ms", t.maxMs())
                            .addField("value", t.totalMs())
                            .time(tsNs, WritePrecision.NS);
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static void add(List<Point> out, long tsNs, String category, String metric, long value) {
        if (value < 0) return; // -1 在 RSS/磁盘/非堆 max 等场景代表"不支持"，直接落库会污染图表
        add(out, tsNs, category, metric, (double) value, Map.of());
    }

    private static void add(List<Point> out, long tsNs, String category, String metric, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return;
        add(out, tsNs, category, metric, value, Map.of());
    }

    private static void add(List<Point> out, long tsNs, String category, String metric, double value,
                            Map<String, String> extraTags) {
        Point p = Point.measurement(MEASUREMENT)
                .addTag("appid", APPID_SELF)
                .addTag("category", category)
                .addTag("metric", metric)
                .addField("value", value)
                .time(tsNs, WritePrecision.NS);
        for (Map.Entry<String, String> e : extraTags.entrySet()) {
            p.addTag(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        out.add(p);
    }

    public Sample latest() {
        synchronized (lock) {
            return ring.peekLast();
        }
    }

    public List<Sample> window(int size) {
        int n = size <= 0 ? Math.min(60, RING_SIZE) : Math.min(size, RING_SIZE);
        synchronized (lock) {
            List<Sample> out = new ArrayList<>(Math.min(n, ring.size()));
            int skip = Math.max(0, ring.size() - n);
            int i = 0;
            for (Sample s : ring) {
                if (i++ < skip) continue;
                out.add(s);
            }
            return out;
        }
    }

    public int size() {
        synchronized (lock) { return ring.size(); }
    }

    private Sample build() {
        long now = System.currentTimeMillis();
        JvmSnap jvm = captureJvm();
        ProcessSnap proc = captureProcess();
        MeterSnap meters = captureMeters();
        return new Sample(now, Instant.ofEpochMilli(now).toString(), jvm, proc, meters);
    }

    private JvmSnap captureJvm() {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        ClassLoadingMXBeanWrap cl = new ClassLoadingMXBeanWrap(ManagementFactory.getClassLoadingMXBean());
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        int deadlocked = 0;
        try {
            long[] ids = tmx.findDeadlockedThreads();
            deadlocked = ids == null ? 0 : ids.length;
        } catch (Throwable ignore) {}
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        List<PoolSnap> pools = new ArrayList<>();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = p.getUsage();
            if (u == null) continue;
            pools.add(new PoolSnap(
                    p.getName(),
                    new MemSnap(u.getUsed(), u.getCommitted(), u.getMax(), u.getInit())
            ));
        }
        List<GcSnap> gcs = new ArrayList<>();
        for (var gm : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcs.add(new GcSnap(gm.getName(), gm.getCollectionCount(), gm.getCollectionTime()));
        }
        return new JvmSnap(
                new MemSnap(heap.getUsed(), heap.getCommitted(), heap.getMax(), heap.getInit()),
                new MemSnap(nonHeap.getUsed(), nonHeap.getCommitted(), nonHeap.getMax() < 0 ? nonHeap.getCommitted() : nonHeap.getMax(), nonHeap.getInit()),
                pools,
                new ThreadSnap(tmx.getThreadCount(), tmx.getDaemonThreadCount(), tmx.getPeakThreadCount(), deadlocked),
                new ClassSnap(cl.loaded(), cl.totalLoaded(), cl.unloaded()),
                uptime,
                gcs
        );
    }

    private ProcessSnap captureProcess() {
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double procCpu = clamp(os.getProcessCpuLoad(), 0d, 1d);
        double sysCpu = clamp(os.getCpuLoad(), 0d, 1d);
        long virt = safe(os::getCommittedVirtualMemorySize, 0L);
        // 真实 RSS:OperatingSystemMXBean 没现成 API,Linux 上读 /proc/self/status 的 VmRSS
        long rss = readRssBytes();
        long heapUsed = (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        long nonHeapUsed = (ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
        long sysTotal = os.getTotalMemorySize();
        long sysFree = os.getFreeMemorySize();
        long diskFree = safe(SelfMonitorCollector::freeDiskBytes, 0L);
        return new ProcessSnap(procCpu, sysCpu, rss, virt, heapUsed, nonHeapUsed, sysTotal, sysFree, diskFree, Runtime.getRuntime().availableProcessors());
    }

    /**
     * 拿当前进程真实 RSS(bytes)。JDK 标准 API 没暴露,这里按平台走两条路:
     * - Linux:读 /proc/self/status 的 VmRSS
     * - Windows:起 powershell 查 (Get-Process -Id <pid>).WorkingSet64
     * - 其它:返回 -1(上层降级)
     */
    private static long readRssBytes() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux") || os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            return readRssBytesLinux();
        }
        if (os.contains("win")) {
            return readRssBytesWindows();
        }
        return -1L;
    }

    private static long readRssBytesLinux() {
        try {
            File f = new File("/proc/self/status");
            if (!f.exists() || !f.canRead()) return -1L;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("VmRSS:")) {
                        // VmRSS:	  123456 kB
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            long kb = Long.parseLong(parts[1]);
                            return kb * 1024L;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log.debug("[spring-watch: SelfMonitorCollector 读Linux VmRSS失败 - error={}]", t.getMessage());
        }
        return -1L;
    }

    private static long readRssBytesWindows() {
        Process p = null;
        try {
            long pid = ProcessHandle.current().pid();
            p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-Process -Id " + pid + " -ErrorAction SilentlyContinue).WorkingSet64")
                    .redirectErrorStream(true)
                    .start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (line != null && !line.isBlank()) {
                    return Long.parseLong(line.trim());
                }
            }
        } catch (Throwable t) {
            log.debug("[spring-watch: SelfMonitorCollector 读Windows WorkingSet64失败 - error={}]", t.getMessage());
        } finally {
            if (p != null && p.isAlive()) {
                try {
                    boolean finished = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                    if (!finished) p.destroyForcibly();
                } catch (Throwable ignore) {
                    p.destroyForcibly();
                }
            }
        }
        return -1L;
    }

    private MeterSnap captureMeters() {
        Map<String, Double> counters = new LinkedHashMap<>();
        Map<String, TimerSnap> timers = new LinkedHashMap<>();
        Map<String, Double> gauges = new LinkedHashMap<>();
        int captured = 0;
        int filtered = 0;
        for (Meter m : meterRegistry.getMeters()) {
            String name = m.getId().getName();
            if (!name.startsWith(METER_PREFIX) || !isWhitelisted(name)) {
                filtered++;
                continue;
            }
            captured++;
            if (m instanceof Counter c) {
                double v = c.count();
                counters.put(name, v);
                lastCounterValues.put(name, v);
            } else if (m instanceof Timer t) {
                TimerSnap snap = new TimerSnap(t.count(), t.totalTime(TimeUnit.MILLISECONDS), -1.0, t.max(TimeUnit.MILLISECONDS));
                timers.put(name, snap);
                lastTimerSnaps.put(name, snap);
            } else if (m instanceof Gauge g) {
                double v = g.value();
                if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                    gauges.put(name, v);
                    lastGaugeValues.put(name, v);
                }
            }
        }
        if (filtered > 0) filteredMeterCounter.increment(filtered);
        if (captured > 0) capturedMeterCounter.increment(captured);
        return new MeterSnap(counters, timers, gauges);
    }

    private static boolean isWhitelisted(String meterName) {
        for (String prefix : METER_WHITELIST_PREFIXES) {
            if (meterName.startsWith(prefix)) return true;
        }
        return false;
    }

    private static long freeDiskBytes() {
        File root = new File(".").getAbsoluteFile();
        while (root.getParentFile() != null && root.getParentFile().getUsableSpace() > 0) {
            root = root.getParentFile();
            if (root.getUsableSpace() > 0 && root.getTotalSpace() > 0) break;
        }
        return root.getUsableSpace();
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0d;
        return Math.max(lo, Math.min(hi, v));
    }

    private static long safe(java.util.function.Supplier<Long> s, long def) {
        try { Long v = s.get(); return v == null ? def : v; } catch (Throwable t) { return def; }
    }

    private record ClassLoadingMXBeanWrap(java.lang.management.ClassLoadingMXBean src) {
        long loaded() { return safe(() -> (long) src.getLoadedClassCount(), 0L); }
        long totalLoaded() { return safe(() -> (long) src.getTotalLoadedClassCount(), 0L); }
        long unloaded() { return safe(() -> (long) src.getUnloadedClassCount(), 0L); }
        private static long safe(java.util.function.Supplier<Long> s, long def) { try { Long v = s.get(); return v == null ? def : v; } catch (Throwable t) { return def; } }
    }

    public record Sample(long ts, String iso, JvmSnap jvm, ProcessSnap process, MeterSnap meters) {}

    public record MemSnap(long used, long committed, long max, long init) {}

    public record JvmSnap(MemSnap heap, MemSnap nonHeap, List<PoolSnap> pools,
                          ThreadSnap threads, ClassSnap classes, long uptimeMs, List<GcSnap> gc) {}

    public record PoolSnap(String name, MemSnap mem) {}

    public record ThreadSnap(int current, int daemon, int peak, int deadlocked) {}

    public record ClassSnap(long loaded, long totalLoaded, long unloaded) {}

    public record GcSnap(String name, long count, long timeMs) {}

    public record ProcessSnap(double cpuLoad, double systemCpuLoad, long rssBytes, long virtualBytes,
                              long heapUsed, long nonHeapUsed,
                              long systemTotalBytes, long systemFreeBytes, long diskFreeBytes, int cpuCores) {}

    public record TimerSnap(long count, double totalMs, double meanMs, double maxMs) {}

    public record MeterSnap(Map<String, Double> counters, Map<String, TimerSnap> timers, Map<String, Double> gauges) {}
}
