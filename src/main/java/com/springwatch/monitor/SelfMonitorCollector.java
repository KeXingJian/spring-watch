package com.springwatch.monitor;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.WriteParameters;
import com.sun.management.OperatingSystemMXBean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.concurrent.BlockingQueue;
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
import java.util.function.Supplier;

/**
 * 自监控采集器 - 定时采样 JVM/进程/系统/Micrometer 指标。
 * P1-6: 缩小 RING_SIZE 至 60（10min 历史）；引入 Meter 白名单，避免高基数 Gauge 撑爆堆。
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
            "spring.watch.inflight.",
            "spring.watch.consumer.",
            "spring.watch.jvm.",
            "spring.watch.influxdb.",
            "spring.watch.system.",
            "spring.watch.process.",
            "spring.watch.alert.",
            "spring.watch.log.",
            "spring.watch.metric.",
            "spring.watch.ingest."
    );

    private final MeterRegistry meterRegistry;
    @Qualifier("selfMetricsWriteApi")
    private final WriteApi writeApi;
    private final WriteParameters selfMetricsWriteParameters;

    private ScheduledExecutorService scheduler;
    private final Deque<Sample> ring = new ArrayDeque<>(RING_SIZE);
    private final Object lock = new Object();

    /** 增量更新：保留每个 meter 上次采样值，避免每次全量遍历拷贝。 */
    private final Map<String, Double> lastCounterValues = new ConcurrentHashMap<>();
    private final Map<String, TimerSnap> lastTimerSnaps = new ConcurrentHashMap<>();
    private final Map<String, Double> lastGaugeValues = new ConcurrentHashMap<>();
    private final Map<String, SummarySnap> lastSummarySnaps = new ConcurrentHashMap<>();
    private Counter filteredMeterCounter;
    private Counter capturedMeterCounter;
    private Counter persistedCounter;
    private Counter persistFailCounter;


    @PostConstruct
    void registerMeters() {
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

        Gauge.builder("spring.watch.self.monitor.ring.size", this, s -> (double) s.size())
                .description("自监控 ring 当前样本数")
                .register(meterRegistry);

        Gauge.builder("spring.watch.jvm.g1.eden.used", SelfMonitorCollector::readEdenUsed)
                .description("G1 Eden Space 已用字节")
                .register(meterRegistry);
        Gauge.builder("spring.watch.jvm.g1.eden.max", SelfMonitorCollector::readEdenMax)
                .description("G1 Eden Space 最大字节")
                .register(meterRegistry);
        Gauge.builder("spring.watch.jvm.g1.oldgen.used", SelfMonitorCollector::readOldGenUsed)
                .description("G1 Old Gen 已用字节")
                .register(meterRegistry);
        Gauge.builder("spring.watch.jvm.g1.oldgen.max", SelfMonitorCollector::readOldGenMax)
                .description("G1 Old Gen 最大字节")
                .register(meterRegistry);
        Gauge.builder("spring.watch.jvm.g1.oldgen.pct", SelfMonitorCollector::readOldGenPct)
                .description("G1 Old Gen 使用率 %")
                .register(meterRegistry);
        Gauge.builder("spring.watch.jvm.g1.survivor.used", SelfMonitorCollector::readSurvivorUsed)
                .description("G1 Survivor Space 已用字节")
                .register(meterRegistry);
        Gauge.builder("spring.watch.jvm.g1.survivor.max", SelfMonitorCollector::readSurvivorMax)
                .description("G1 Survivor Space 最大字节")
                .register(meterRegistry);

        Gauge.builder("spring.watch.jvm.threads.current", () ->
                        (double) ManagementFactory.getThreadMXBean().getThreadCount())
                .description("当前 JVM 线程数")
                .register(meterRegistry);
        Gauge.builder("spring.watch.jvm.threads.daemon", () ->
                        (double) ManagementFactory.getThreadMXBean().getDaemonThreadCount())
                .description("守护线程数")
                .register(meterRegistry);
        Gauge.builder("spring.watch.jvm.threads.peak", () ->
                        (double) ManagementFactory.getThreadMXBean().getPeakThreadCount())
                .description("JVM 启动后峰值线程数")
                .register(meterRegistry);
        Gauge.builder("spring.watch.jvm.classes.loaded", () ->
                        (double) ManagementFactory.getClassLoadingMXBean().getLoadedClassCount())
                .description("当前已加载类数")
                .register(meterRegistry);

        Gauge.builder("spring.watch.influxdb.write.queue.size", writeApi, SelfMonitorCollector::readWriteApiQueueSize)
                .description("InfluxDB WriteApi 内部 writeQueue 堆积(反射读,取不到时 -1)")
                .register(meterRegistry);
        Gauge.builder("spring.watch.influxdb.write.point.queued", writeApi, SelfMonitorCollector::readWriteApiPendingPoints)
                .description("InfluxDB WriteApi 内部 pending points 估算")
                .register(meterRegistry);
        this.scheduler = null;
    }

    /**
     * 延后到 ApplicationReadyEvent 触发后再启动 scheduler。
     * 此时:
     *   - Flyway 全部迁移已跑完,alert_history / alert_rule 等表已存在
     *   - InfluxDBBucketInitializer / InfraMetricsBucketInitializer 已建好 self_metrics / infra_metrics bucket
     *   - InflightQueue 已启动 K×3 个 partition,InflightMetrics 7 个指标已注册
     *   - InflightConsumer N 个虚拟线程已启动消费
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "self-monitor-sampler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::sample, 0L, SAMPLE_INTERVAL_SEC, TimeUnit.SECONDS);
        log.info("[kxj: SelfMonitorCollector 启动(延后到 ApplicationReadyEvent) - interval={}s, ring={}, whitelist={}]",
                SAMPLE_INTERVAL_SEC, RING_SIZE, METER_WHITELIST_PREFIXES.size());
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        log.info("[kxj: SelfMonitorCollector 关闭 - samples={}]", ring.size());
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
            log.warn("[kxj: SelfMonitorCollector 采样异常 - error={}]", t.getMessage());
        }
    }

    /**
     * 把一次 sample 扁平化后批量写入 InfluxDB self_metrics 桶。
     * 与 InflightConsumer → MetricEventWriter 走同一个 WriteApi（共享写缓冲/重试/限速），失败仅打点不抛。
     */
    private void persist(Sample s) {
        List<Point> points;
        try {
            points = toPoints(s);
        } catch (Throwable t) {
            persistFailCounter.increment();
            log.warn("[kxj: SelfMonitorCollector 转换InfluxDB Point失败 - error={}]", t.getMessage());
            return;
        }
        if (points.isEmpty()) return;
        try {
            writeApi.writePoints(points, selfMetricsWriteParameters);
            persistedCounter.increment(points.size());
        } catch (Throwable t) {
            persistFailCounter.increment();
            log.warn("[kxj: SelfMonitorCollector 写InfluxDB失败 - size={}, error={}]",
                    points.size(), t.getMessage());
        }
    }

    /**
     * 把 Sample 拆成扁平 InfluxDB Point 列表：
     * - jvm 段 → category=jvm, metric=heap.used / nonHeap.used / pool.used(pool_name) / gc.count(gc_name) / gc.time_ms(gc_name) ...
     * - process 段 → category=process, metric=cpu_load / rss_bytes / ...
     * - meters 段 → category=meter, meter_type=counter|gauge|timer, metric=<原始 meter 名>
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
            if (jvm.pools() != null) {
                for (PoolSnap p : jvm.pools()) {
                    if (p == null || p.name() == null || p.mem() == null) continue;
                    add(out, tsNs, CAT_JVM, "pool.used", p.mem().used(), Map.of("pool_name", p.name()));
                    add(out, tsNs, CAT_JVM, "pool.committed", p.mem().committed(), Map.of("pool_name", p.name()));
                    if (p.mem().max() > 0) {
                        add(out, tsNs, CAT_JVM, "pool.max", p.mem().max(), Map.of("pool_name", p.name()));
                    }
                }
            }
        }

        ProcessSnap proc = s.process;
        if (proc != null) {
            add(out, tsNs, "cpu_load", proc.cpuLoad());
            add(out, tsNs, "system_cpu_load", proc.systemCpuLoad());
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
                for (Map.Entry<String, List<MeterEntry>> e : meters.counters().entrySet()) {
                    if (e.getValue() == null) continue;
                    for (MeterEntry me : e.getValue()) {
                        if (me == null) continue;
                        add(out, tsNs, CAT_METER, e.getKey(), me.value(), mergeTags("counter", me.tags()));
                    }
                }
            }
            if (meters.gauges() != null) {
                for (Map.Entry<String, List<MeterEntry>> e : meters.gauges().entrySet()) {
                    if (e.getValue() == null) continue;
                    for (MeterEntry me : e.getValue()) {
                        if (me == null) continue;
                        add(out, tsNs, CAT_METER, e.getKey(), me.value(), mergeTags("gauge", me.tags()));
                    }
                }
            }
            if (meters.timers() != null) {
                for (Map.Entry<String, List<TimerMeterEntry>> e : meters.timers().entrySet()) {
                    if (e.getValue() == null) continue;
                    for (TimerMeterEntry te : e.getValue()) {
                        if (te == null || te.snap() == null) continue;
                        TimerSnap t = te.snap();
                        Point p = Point.measurement(MEASUREMENT)
                                .addTag("appid", APPID_SELF)
                                .addTag("category", CAT_METER)
                                .addTag("meter_type", "timer")
                                .addTag("metric", e.getKey());
                        for (Map.Entry<String, String> tag : mergeTags(null, te.tags()).entrySet()) {
                            p.addTag(tag.getKey(), tag.getValue() == null ? "" : tag.getValue());
                        }
                        p.addField("count", t.count())
                                .addField("total_ms", t.totalMs())
                                .addField("max_ms", t.maxMs())
                                .addField("value", t.totalMs())
                                .time(tsNs, WritePrecision.NS);
                        out.add(p);
                    }
                }
            }
            if (meters.summaries() != null) {
                for (Map.Entry<String, List<SummaryMeterEntry>> e : meters.summaries().entrySet()) {
                    if (e.getValue() == null) continue;
                    for (SummaryMeterEntry se : e.getValue()) {
                        if (se == null || se.snap() == null) continue;
                        SummarySnap ss = se.snap();
                        // mean 走 field=value,带 meter_type=summary,无 quantile
                        addSummary(out, tsNs, e.getKey(), se.tags(), null, ss.mean());
                        // 展开 p50 / p95 / p99,带 quantile=0.5/0.95/0.99 tag 区分
                        for (Map.Entry<String, Double> p : ss.percentiles().entrySet()) {
                            addSummary(out, tsNs, e.getKey(), se.tags(), p.getKey(), p.getValue());
                        }
                    }
                }
            }
        }
        return out;
    }

    /** 把 meter_type 与 meter 自带 tag 合并,空 tag 自动跳过。 */
    private static Map<String, String> mergeTags(String meterType, Map<String, String> meterTags) {
        Map<String, String> out = new LinkedHashMap<>();
        if (meterType != null) out.put("meter_type", meterType);
        if (meterTags == null) return out;
        for (Map.Entry<String, String> e : meterTags.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            if ("appid".equals(e.getKey())) continue;
            out.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static void add(List<Point> out, long tsNs, String category, String metric, long value) {
        if (value < 0) return; // -1 在 RSS/磁盘/非堆 max 等场景代表"不支持"，直接落库会污染图表
        add(out, tsNs, category, metric, (double) value, Map.of());
    }

    private static void add(List<Point> out, long tsNs, String metric, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return;
        add(out, tsNs, SelfMonitorCollector.CAT_PROCESS, metric, value, Map.of());
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

    /**
     * DistributionSummary 写点:固定 meter_type=summary,field=value。
     * quantile 非空时额外带 quantile tag,与 Prometheus 风格一致,前端按 quantile 过滤拿 p50/p95/p99。
     */
    private static void addSummary(List<Point> out, long tsNs, String metric,
                                   Map<String, String> meterTags, String quantile, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return;
        Point p = Point.measurement(MEASUREMENT)
                .addTag("appid", APPID_SELF)
                .addTag("category", CAT_METER)
                .addTag("meter_type", "summary")
                .addTag("metric", metric);
        for (Map.Entry<String, String> tag : mergeTags(null, meterTags).entrySet()) {
            p.addTag(tag.getKey(), tag.getValue() == null ? "" : tag.getValue());
        }
        if (quantile != null && !quantile.isBlank()) {
            p.addTag("quantile", quantile);
        }
        p.addField("value", value)
                .time(tsNs, WritePrecision.NS);
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
        double procCpu = clamp(os.getProcessCpuLoad());
        double sysCpu = clamp(os.getCpuLoad());
        long virt = safe(os::getCommittedVirtualMemorySize);
        // 真实 RSS:OperatingSystemMXBean 没现成 API,Linux 上读 /proc/self/status 的 VmRSS
        long rss = readRssBytes();
        long heapUsed = (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        long nonHeapUsed = (ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
        long sysTotal = os.getTotalMemorySize();
        long sysFree = os.getFreeMemorySize();
        long diskFree = safe(SelfMonitorCollector::freeDiskBytes);
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
            log.debug("[kxj: SelfMonitorCollector 读Linux VmRSS失败 - error={}]", t.getMessage());
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
            log.debug("[kxj: SelfMonitorCollector 读Windows WorkingSet64失败 - error={}]", t.getMessage());
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
        Map<String, List<MeterEntry>> counters = new LinkedHashMap<>();
        Map<String, List<TimerMeterEntry>> timers = new LinkedHashMap<>();
        Map<String, List<MeterEntry>> gauges = new LinkedHashMap<>();
        Map<String, List<SummaryMeterEntry>> summaries = new LinkedHashMap<>();
        int captured = 0;
        int filtered = 0;
        for (Meter m : meterRegistry.getMeters()) {
            String name = m.getId().getName();
            if (!name.startsWith(METER_PREFIX) || !isWhitelisted(name)) {
                filtered++;
                continue;
            }
            captured++;
            Map<String, String> tags = new LinkedHashMap<>();
            for (Tag t : m.getId().getTags()) {
                tags.put(t.getKey(), t.getValue());
            }
            switch (m) {
                case Counter c -> {
                    double v = c.count();
                    counters.computeIfAbsent(name, _ -> new ArrayList<>()).add(new MeterEntry(v, tags));
                    lastCounterValues.put(name, v);
                }
                case Timer t -> {
                    TimerSnap snap = new TimerSnap(t.count(), t.totalTime(TimeUnit.MILLISECONDS), -1.0, t.max(TimeUnit.MILLISECONDS));
                    timers.computeIfAbsent(name, _ -> new ArrayList<>()).add(new TimerMeterEntry(snap, tags));
                    lastTimerSnaps.put(name, snap);
                }
                case Gauge g -> {
                    double v = g.value();
                    if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                        gauges.computeIfAbsent(name, _ -> new ArrayList<>()).add(new MeterEntry(v, tags));
                        lastGaugeValues.put(name, v);
                    }
                }
                case DistributionSummary ds -> {
                    // publishPercentiles 配过的 percentile 由 takeSnapshot 拿到;
                    // 同时把 mean 也算出来,前端想看平均值时直接走无 quantile 那个 Point。
                    HistogramSnapshot snap = ds.takeSnapshot();
                    double count = snap.count();
                    double total = snap.total();
                    double mean = count > 0 ? total / count : 0d;
                    Map<String, Double> percentiles = new LinkedHashMap<>();
                    for (ValueAtPercentile vap : snap.percentileValues()) {
                        percentiles.put(formatQuantile(vap.percentile()), vap.value());
                    }
                    SummarySnap ss = new SummarySnap(count, total, mean, percentiles);
                    summaries.computeIfAbsent(name, _ -> new ArrayList<>()).add(new SummaryMeterEntry(ss, tags));
                    lastSummarySnaps.put(name, ss);
                }
                default -> {
                }
            }
        }
        if (filtered > 0) filteredMeterCounter.increment(filtered);
        if (captured > 0) capturedMeterCounter.increment(captured);
        return new MeterSnap(counters, timers, gauges, summaries);
    }

    /**
     * Micrometer 的 percentile 是 0~1 小数,InfluxDB tag 用字符串存,统一保留两位小数
     * (0.5 / 0.95 / 0.99 等),前端过滤时直接传 "0.5" 即可命中。
     */
    private static String formatQuantile(double q) {
        return String.format(java.util.Locale.ROOT, "%.2f", q);
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

    private static double clamp(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0d;
        return Math.clamp(v, 0.0, 1.0);
    }

    private static long safe(Supplier<Long> s) {
        try { Long v = s.get(); return v == null ? 0L : v; } catch (Throwable t) { return 0L; }
    }

    private static double poolBytes(String poolName, java.util.function.ToLongFunction<MemoryUsage> extractor) {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (poolName.equals(p.getName())) {
                MemoryUsage u = p.getUsage();
                if (u == null) return 0d;
                long v = extractor.applyAsLong(u);
                return v < 0 ? 0d : (double) v;
            }
        }
        return 0d;
    }

    private static double readEdenUsed()    { return poolBytes("G1 Eden Space", MemoryUsage::getUsed); }
    private static double readEdenMax()     { return poolBytes("G1 Eden Space", MemoryUsage::getMax); }
    private static double readOldGenUsed()  { return poolBytes("G1 Old Gen", MemoryUsage::getUsed); }
    private static double readOldGenMax()   { return poolBytes("G1 Old Gen", MemoryUsage::getMax); }
    private static double readOldGenPct() {
        double used = readOldGenUsed();
        double max = readOldGenMax();
        if (max <= 0d) return 0d;
        return Math.min(100d, used / max * 100d);
    }
    private static double readSurvivorUsed() { return poolBytes("G1 Survivor Space", MemoryUsage::getUsed); }
    private static double readSurvivorMax()  { return poolBytes("G1 Survivor Space", MemoryUsage::getMax); }

    private static double readWriteApiQueueSize(WriteApi w) {
        if (w == null) return -1d;
        for (String fieldName : new String[]{"writeQueue", "pendingWrites", "dataPoints", "dataPointQueue"}) {
            try {
                Field f = w.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object q = f.get(w);
                if (q instanceof BlockingQueue<?> bq) {
                    return bq.size();
                }
                if (q instanceof java.util.Collection<?> c) {
                    return c.size();
                }
            } catch (NoSuchFieldException ignore) {
            } catch (Throwable ignore) {
                return -1d;
            }
        }
        return -1d;
    }

    private static double readWriteApiPendingPoints(WriteApi w) {
        if (w == null) return -1d;
        for (String fieldName : new String[]{"writeQueue", "pendingWrites", "dataPoints", "dataPointQueue"}) {
            try {
                Field f = w.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object q = f.get(w);
                if (q instanceof BlockingQueue<?> bq) {
                    int count = 0;
                    for (Object item : bq) {
                        if (item == null) continue;
                        String s = item.toString();
                        int idx = s.indexOf("points=");
                        if (idx > 0) {
                            int end = s.indexOf(',', idx);
                            if (end < 0) end = s.length();
                            try {
                                count += Integer.parseInt(s.substring(idx + 7, end).trim());
                            } catch (NumberFormatException ignore) {}
                        } else {
                            count++;
                        }
                    }
                    return count;
                }
            } catch (NoSuchFieldException ignore) {
            } catch (Throwable ignore) {
                return -1d;
            }
        }
        return -1d;
    }

    private record ClassLoadingMXBeanWrap(java.lang.management.ClassLoadingMXBean src) {
        long loaded() { return safe(() -> (long) src.getLoadedClassCount()); }
        long totalLoaded() { return safe(src::getTotalLoadedClassCount); }
        long unloaded() { return safe(src::getUnloadedClassCount); }
        private static long safe(Supplier<Long> s) { try { Long v = s.get(); return v == null ? 0L : v; } catch (Throwable t) { return 0L; } }
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

    public record MeterSnap(Map<String, List<MeterEntry>> counters, Map<String, List<TimerMeterEntry>> timers, Map<String, List<MeterEntry>> gauges, Map<String, List<SummaryMeterEntry>> summaries) {}

    public record MeterEntry(double value, Map<String, String> tags) {}

    public record TimerMeterEntry(TimerSnap snap, Map<String, String> tags) {}

    /**
     * DistributionSummary 快照:count/total/mean + 展开后的 percentile 映射(quantile 字符串 → 值)。
     * 注意 percentile 是按 publishPercentiles 注册顺序写入,key 形如 "0.50" / "0.95" / "0.99"。
     */
    public record SummarySnap(double count, double total, double mean, Map<String, Double> percentiles) {}

    public record SummaryMeterEntry(SummarySnap snap, Map<String, String> tags) {}
}
