package com.springwatch.monitor;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * kxj: 自监控采集器 - 定时采样 JVM/进程/系统/Micrometer 指标
 * 保留最近 1h 快照(RingBuffer,采样间隔 10s)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfMonitorCollector {

    private static final long SAMPLE_INTERVAL_SEC = 10L;
    private static final int RING_SIZE = 360;
    private static final String METER_PREFIX = "spring.watch.";

    private final MeterRegistry meterRegistry;
    private final KafkaFallbackQueue kafkaFallbackQueue;
    private final HostThrottler hostThrottler;

    private ScheduledExecutorService scheduler;
    private final Deque<Sample> ring = new ArrayDeque<>(RING_SIZE);
    private final Object lock = new Object();

    @PostConstruct
    void start() {
        Gauge.builder("spring.watch.collector.kafka.fallback.size", kafkaFallbackQueue, KafkaFallbackQueue::size)
                .description("Kafka 兜底队列当前堆积")
                .register(meterRegistry);
        Gauge.builder("spring.watch.collector.host_throttler.active", hostThrottler, h -> (double) h.activeHosts())
                .description("已注册主机限流器数量")
                .register(meterRegistry);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "self-monitor-sampler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::sample, 0L, SAMPLE_INTERVAL_SEC, TimeUnit.SECONDS);
        log.info("[spring-watch: SelfMonitorCollector 启动 - interval={}s, ring={}]", SAMPLE_INTERVAL_SEC, RING_SIZE);
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
        } catch (Throwable t) {
            log.warn("[spring-watch: SelfMonitorCollector 采样异常 - error={}]", t.getMessage());
        }
    }

    public Sample latest() {
        synchronized (lock) {
            return ring.peekLast();
        }
    }

    public List<Sample> window(int size) {
        int n = size <= 0 ? 60 : Math.min(size, RING_SIZE);
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
        long metaspaceUsed = 0L, metaspaceCommitted = 0L;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if ("Metaspace".equalsIgnoreCase(p.getName())) {
                MemoryUsage u = p.getUsage();
                if (u != null) {
                    metaspaceUsed = u.getUsed();
                    metaspaceCommitted = u.getCommitted();
                }
            }
        }
        List<GcSnap> gcs = new ArrayList<>();
        for (var gm : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcs.add(new GcSnap(gm.getName(), gm.getCollectionCount(), gm.getCollectionTime()));
        }
        return new JvmSnap(
                new MemSnap(heap.getUsed(), heap.getCommitted(), heap.getMax(), heap.getInit()),
                new MemSnap(nonHeap.getUsed(), nonHeap.getCommitted(), nonHeap.getMax() < 0 ? nonHeap.getCommitted() : nonHeap.getMax(), nonHeap.getInit()),
                new MemSnap(metaspaceUsed, metaspaceCommitted, -1L, -1L),
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
        long heapUsed = (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        long sysTotal = os.getTotalMemorySize();
        long sysFree = os.getFreeMemorySize();
        long diskFree = safe(SelfMonitorCollector::freeDiskBytes, 0L);
        return new ProcessSnap(procCpu, sysCpu, heapUsed, virt, sysTotal, sysFree, diskFree, Runtime.getRuntime().availableProcessors());
    }

    private MeterSnap captureMeters() {
        Map<String, Double> counters = new LinkedHashMap<>();
        Map<String, TimerSnap> timers = new LinkedHashMap<>();
        Map<String, Double> gauges = new LinkedHashMap<>();
        for (Meter m : meterRegistry.getMeters()) {
            String name = m.getId().getName();
            if (!name.startsWith(METER_PREFIX)) continue;
            if (m instanceof Counter c) {
                counters.put(name, c.count());
            } else if (m instanceof Timer t) {
                timers.put(name, new TimerSnap(t.count(), t.totalTime(TimeUnit.MILLISECONDS), -1.0, t.max(TimeUnit.MILLISECONDS)));
            } else if (m instanceof Gauge g) {
                double v = g.value();
                if (!Double.isNaN(v) && !Double.isInfinite(v)) gauges.put(name, v);
            }
        }
        return new MeterSnap(counters, timers, gauges);
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

    public record JvmSnap(MemSnap heap, MemSnap nonHeap, MemSnap metaspace,
                          ThreadSnap threads, ClassSnap classes, long uptimeSec, List<GcSnap> gc) {}

    public record ThreadSnap(int current, int daemon, int peak, int deadlocked) {}

    public record ClassSnap(long loaded, long totalLoaded, long unloaded) {}

    public record GcSnap(String name, long count, long timeMs) {}

    public record ProcessSnap(double cpuLoad, double systemCpuLoad, long rssBytes, long virtualBytes,
                              long systemTotalBytes, long systemFreeBytes, long diskFreeBytes, int cpuCores) {}

    public record TimerSnap(long count, double totalMs, double meanMs, double maxMs) {}

    public record MeterSnap(Map<String, Double> counters, Map<String, TimerSnap> timers, Map<String, Double> gauges) {}
}
