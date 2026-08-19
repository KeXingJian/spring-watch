package com.springwatch.agent.metric;

import com.sun.management.OperatingSystemMXBean;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标机 OS / 进程级指标(零依赖:仅 JDK API + Linux {@code /proc} 文本解析)。
 * <p>
 * 命名空间对齐 OpenTelemetry semantic conventions 与平台前端 {@code useAppView.ts: osViewSpecs()}:
 * <ul>
 *   <li>{@code jvm_cpu_count}:来自 {@link JvmMetricsProvider};此处不重复注册。</li>
 *   <li>{@code system_memory_utilization{state=used}} / {@code system_memory_usage_bytes{state=total|free|used}}</li>
 *   <li>{@code runtime_java_memory_bytes{type=rss|vms}}</li>
 *   <li>{@code runtime_java_cpu_time_milliseconds{type=user|system}}</li>
 *   <li>{@code process_cpu_utilization} / {@code process_cpu_time_seconds_total} / {@code process_uptime_seconds}</li>
 *   <li>{@code system_load_average_1m} / {@code 5m} / {@code 15m} (仅 Linux)</li>
 *   <li>{@code system_disk_io_bytes_total{device,direction=read|write}} 与 {@code system_disk_operations_total}</li>
 *   <li>{@code system_network_io_bytes_total{device,direction=receive|transmit}} + packets + errors</li>
 * </ul>
 * 非 Linux 平台相关字段不注册(空数据,不报错)。
 */
public final class OsMetricsProvider {

    private static final boolean IS_LINUX = System.getProperty("os.name", "").toLowerCase().contains("linux");
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private final MetricRegistry registry;
    private final java.lang.management.OperatingSystemMXBean osJdk;
    private final OperatingSystemMXBean osSun;
    private final long pid;

    public OsMetricsProvider(MetricRegistry registry) {
        this.registry = registry;
        this.osJdk = ManagementFactory.getOperatingSystemMXBean();
        this.osSun = (osJdk instanceof OperatingSystemMXBean) ? (OperatingSystemMXBean) osJdk : null;
        this.pid = ProcessHandle.current().pid();
    }

    public void register() {
        registerMemoryMetrics();
        registerRuntimeMemoryMetrics();
        registerRuntimeCpuMetrics();
        registerProcessMetrics();
        if (IS_LINUX) {
            registerLoadAvgMetrics();
            registerLinuxProcCache();
        }
    }

    private void registerMemoryMetrics() {
        Gauge usage = registry.gauge("system_memory_usage_bytes", "System memory usage in bytes.");
        Gauge util = registry.gauge("system_memory_utilization", "System memory utilization (0..1).");

        LongSupplierEx totalFn = IS_LINUX ? this::readMemTotal : (this.osSun == null ? null : this::jdkTotalMemory);
        LongSupplierEx freeFn  = IS_LINUX ? this::readMemFree  : (this.osSun == null ? null : this::jdkFreeMemory);

        if (totalFn != null && freeFn != null) {
            usage.register(Labels.of("state", "total"),
                    () -> safeLong(totalFn));
            usage.register(Labels.of("state", "free"),
                    () -> safeLong(freeFn));
            usage.register(Labels.of("state", "used"),
                    () -> Math.max(0L, safeLong(totalFn) - safeLong(freeFn)));
            util.register(Labels.of("state", "used"),
                    () -> {
                        long t = (long) safeLong(totalFn);
                        long f = (long) safeLong(freeFn);
                        return t > 0 ? Math.min(1d, (double) (t - f) / t) : 0d;
                    });
        }
    }

    private void registerRuntimeMemoryMetrics() {
        Gauge mem = registry.gauge("runtime_java_memory_bytes", "JVM process memory bytes by type.");
        mem.register(Labels.of("type", "rss"), this::readRssBytes);
        if (osSun != null) {
            mem.register(Labels.of("type", "vms"), () -> safeLong(() -> osSun.getCommittedVirtualMemorySize()));
        }
    }

    private void registerRuntimeCpuMetrics() {
        if (!IS_LINUX) return;
        Gauge t = registry.gauge("runtime_java_cpu_time_milliseconds",
                "JVM process CPU time in milliseconds by type (user/system).");
        ProcStatSnapshot last = new ProcStatSnapshot();
        t.register(Labels.of("type", "user"), () -> last.cached().userJiffies);
        t.register(Labels.of("type", "system"), () -> last.cached().systemJiffies);
    }

    private void registerProcessMetrics() {
        Gauge cpu = registry.gauge("process_cpu_utilization",
                "Recent process CPU utilization (0..1).");
        cpu.register(Labels.EMPTY, () -> {
            if (osSun == null) return 0d;
            double v = osSun.getProcessCpuLoad();
            return Double.isNaN(v) || Double.isInfinite(v) ? 0d : Math.clamp(v, 0d, 1d);
        });
        Gauge total = registry.gauge("process_cpu_time_seconds_total",
                "Total CPU time consumed by the process in seconds.");
        total.register(Labels.EMPTY, () -> osSun == null ? 0d : osSun.getProcessCpuTime() / 1_000_000_000.0);

        Gauge uptime = registry.gauge("process_uptime_seconds",
                "Process uptime in seconds.");
        uptime.register(Labels.EMPTY, () -> ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
    }

    private void registerLoadAvgMetrics() {
        Gauge g1 = registry.gauge("system_load_average_1m", "System load average over 1 minute.");
        Gauge g5 = registry.gauge("system_load_average_5m", "System load average over 5 minutes.");
        Gauge g15 = registry.gauge("system_load_average_15m", "System load average over 15 minutes.");
        g1.register(Labels.EMPTY, () -> readLoadAvg(0));
        g5.register(Labels.EMPTY, () -> readLoadAvg(1));
        g15.register(Labels.EMPTY, () -> readLoadAvg(2));
    }

    /**
     * 磁盘 / 网络指标在 Linux 上读 {@code /proc/diskstats} 和 {@code /proc/net/dev}。
     * <p>
     * 这些是 monotonic 累计值(从开机起);前端 query 用 {@code agg=rate} + derivative,
     * 这里直接 emit cumulative,gauge 类型。
     */
    private void registerLinuxProcCache() {
        Gauge diskBytes = registry.gauge("system_disk_io_bytes_total",
                "Disk I/O bytes transferred since boot (cumulative; per device).");
        Gauge diskOps = registry.gauge("system_disk_operations_total",
                "Disk I/O operation count since boot (cumulative; per device).");
        Gauge netBytes = registry.gauge("system_network_io_bytes_total",
                "Network I/O bytes since boot (cumulative; per interface).");
        Gauge netPkts = registry.gauge("system_network_packets_total",
                "Network packet count since boot (cumulative; per interface).");
        Gauge netErrs = registry.gauge("system_network_errors_total",
                "Network error count since boot (cumulative; per interface).");

        DiskNetCache cache = new DiskNetCache();

        DiskNetCache.Snapshot snap = cache.cached();
        for (Map.Entry<String, long[]> e : snap.disks.entrySet()) {
            String dev = e.getKey();
            diskBytes.register(Labels.of("device", dev, "direction", "read"), () -> readDiskBytes(cache, dev, 0));
            diskBytes.register(Labels.of("device", dev, "direction", "write"), () -> readDiskBytes(cache, dev, 1));
            diskOps.register(Labels.of("device", dev, "direction", "read"), () -> readDiskOps(cache, dev, 0));
            diskOps.register(Labels.of("device", dev, "direction", "write"), () -> readDiskOps(cache, dev, 1));
        }
        for (String iface : snap.interfaces) {
            netBytes.register(Labels.of("device", iface, "direction", "receive"), () -> readNetBytes(cache, iface, 0));
            netBytes.register(Labels.of("device", iface, "direction", "transmit"), () -> readNetBytes(cache, iface, 1));
            netPkts.register(Labels.of("device", iface, "direction", "receive"), () -> readNetPkts(cache, iface, 0));
            netPkts.register(Labels.of("device", iface, "direction", "transmit"), () -> readNetPkts(cache, iface, 1));
            netErrs.register(Labels.of("device", iface, "direction", "receive"), () -> readNetErrs(cache, iface, 0));
            netErrs.register(Labels.of("device", iface, "direction", "transmit"), () -> readNetErrs(cache, iface, 1));
        }
    }

    private double readDiskBytes(DiskNetCache c, String dev, int rwIdx) {
        return c.cached().disks.getOrDefault(dev, EMPTY_DISK)[rwIdx];
    }

    private double readDiskOps(DiskNetCache c, String dev, int rwIdx) {
        return c.cached().disks.getOrDefault(dev, EMPTY_DISK)[rwIdx + 2];
    }

    private double readNetBytes(DiskNetCache c, String iface, int idx) {
        return c.cached().net.getOrDefault(iface, EMPTY_NET)[idx];
    }

    private double readNetPkts(DiskNetCache c, String iface, int idx) {
        return c.cached().net.getOrDefault(iface, EMPTY_NET)[idx + 2];
    }

    private double readNetErrs(DiskNetCache c, String iface, int idx) {
        return c.cached().net.getOrDefault(iface, EMPTY_NET)[idx + 4];
    }

    private static final long[] EMPTY_DISK = new long[]{0L, 0L, 0L, 0L};
    private static final long[] EMPTY_NET = new long[]{0L, 0L, 0L, 0L, 0L, 0L};

    private long jdkTotalMemory() {
        return osSun.getTotalMemorySize();
    }

    private long jdkFreeMemory() {
        return osSun.getFreeMemorySize();
    }

    private long readMemTotal() {
        return readMeminfoKb("MemTotal:");
    }

    private long readMemFree() {
        return readMeminfoKb("MemFree:") + readMeminfoKb("Buffers:") + readMeminfoKb("Cached:");
    }

    private static long readMeminfoKb(String key) {
        File f = new File("/proc/meminfo");
        if (!f.canRead()) return 0L;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(key)) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (Throwable ignore) {
        }
        return 0L;
    }

    private long readRssBytes() {
        if (IS_LINUX) return readRssLinux();
        if (IS_WINDOWS) return readRssWindows();
        return 0L;
    }

    private static long readRssLinux() {
        File f = new File("/proc/self/status");
        if (!f.canRead()) return 0L;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (Throwable ignore) {
        }
        return 0L;
    }

    private long readRssWindows() {
        Process p = null;
        try {
            p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-Process -Id " + pid + " -ErrorAction SilentlyContinue).WorkingSet64")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (line != null && !line.isBlank()) return Long.parseLong(line.trim());
            }
        } catch (Throwable ignore) {
        } finally {
            if (p != null && p.isAlive()) {
                try {
                    if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
                } catch (Throwable ignore) {
                    p.destroyForcibly();
                }
            }
        }
        return 0L;
    }

    private static double readLoadAvg(int idx) {
        File f = new File("/proc/loadavg");
        if (!f.canRead()) return 0d;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            if (line == null) return 0d;
            String[] parts = line.split("\\s+");
            if (parts.length <= idx) return 0d;
            return Double.parseDouble(parts[idx]);
        } catch (Throwable ignore) {
        }
        return 0d;
    }

    private static double safeLong(LongSupplierEx s) {
        try {
            long v = s.getAsLong();
            return v < 0 ? 0d : (double) v;
        } catch (Throwable t) {
            return 0d;
        }
    }

    @FunctionalInterface
    private interface LongSupplierEx {
        long getAsLong() throws Throwable;
    }

    /**
     * 缓存 + 周期刷新 {@code /proc/self/stat} 的 utime/stime(jiffies)以填充
     * {@code runtime_java_cpu_time_milliseconds}。每 scrape 读 /proc/loadavg 与
     * diskstats 都会走 OS 缓存文件,这里只缓存 CPU 时间以避免密集读 /proc/self/stat。
     */
    private final class ProcStatSnapshot {
        private static final long CLK_TCK = readClockTicksPerSec();
        private volatile Snapshot cached = new Snapshot(0L, 0L, System.currentTimeMillis(), false);

        Snapshot cached() {
            Snapshot s = cached;
            long now = System.currentTimeMillis();
            if (s.valid && now - s.at < 1000L) return s;
            long[] v = readSelfStatUtimeStime();
            cached = new Snapshot(toMs(v[0]), toMs(v[1]), now, true);
            return cached;
        }

        private static long readClockTicksPerSec() {
            Process p = null;
            try {
                p = new ProcessBuilder("getconf", "CLK_TCK").redirectErrorStream(true).start();
                try (java.io.InputStream is = p.getInputStream();
                     java.io.BufferedReader r = new java.io.BufferedReader(
                             new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    if (line != null && !line.isBlank()) return Long.parseLong(line.trim());
                }
            } catch (Throwable ignore) {
            } finally {
                if (p != null && p.isAlive()) {
                    try { p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignore) {}
                    p.destroyForcibly();
                }
            }
            return 100L;
        }

        private static long toMs(long jiffies) {
            return jiffies * 1000L / Math.max(1L, CLK_TCK);
        }

        private long[] readSelfStatUtimeStime() {
            long[] out = new long[]{0L, 0L};
            File f = new File("/proc/self/stat");
            if (!f.canRead()) return out;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line = r.readLine();
                if (line == null) return out;
                int rparen = line.lastIndexOf(')');
                if (rparen < 0) return out;
                String[] parts = line.substring(rparen + 2).split("\\s+");
                if (parts.length >= 13) {
                    out[0] = Long.parseLong(parts[11]);
                    out[1] = Long.parseLong(parts[12]);
                }
            } catch (Throwable ignore) {
            }
            return out;
        }

        private record Snapshot(long userJiffies, long systemJiffies, long at, boolean valid) {}
    }

    /**
     * {@code /proc/diskstats} + {@code /proc/net/dev} 的解析缓存。
     * <p>
     * 每次 {@code cached()} 调用读一次文件(IOException 兜底返回空快照),平台 scrape 频率通常 30s+
     * 不会成为瓶颈。diskstats 过滤掉 loop/ram/dm-/md- 等无关设备,net 过滤掉 lo。
     */
    private static final class DiskNetCache {
        private static final String[] SKIP_DISK_PREFIXES = {"loop", "ram", "dm-", "md", "sr", "fd"};
        private static final String[] SKIP_NET_IFACES = {"lo"};

        private volatile Snapshot cached = new Snapshot(Map.of(), Map.of(), java.util.Set.of(), 0L, false);

        Snapshot cached() {
            Snapshot s = cached;
            long now = System.currentTimeMillis();
            if (s.valid && now - s.at < 1000L) return s;
            Map<String, long[]> disks = readDiskstats();
            Map<String, long[]> net = readNetDev();
            cached = new Snapshot(disks, net, net.keySet(), now, true);
            return cached;
        }

        private static Map<String, long[]> readDiskstats() {
            Map<String, long[]> out = new HashMap<>();
            File f = new File("/proc/diskstats");
            if (!f.canRead()) return out;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 14) continue;
                    String dev = p[2];
                    if (shouldSkipDisk(dev)) continue;
                    long readBytes = Long.parseLong(p[5]) * 512L;
                    long writeBytes = Long.parseLong(p[9]) * 512L;
                    long readOps = Long.parseLong(p[3]);
                    long writeOps = Long.parseLong(p[7]);
                    out.put(dev, new long[]{readBytes, writeBytes, readOps, writeOps});
                }
            } catch (Throwable ignore) {
            }
            return out;
        }

        private static boolean shouldSkipDisk(String dev) {
            for (String p : SKIP_DISK_PREFIXES) {
                if (dev.startsWith(p)) return true;
            }
            return false;
        }

        private static Map<String, long[]> readNetDev() {
            Map<String, long[]> out = new LinkedHashMap<>();
            File f = new File("/proc/net/dev");
            if (!f.canRead()) return out;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String iface = line.substring(0, colon).trim();
                    if (shouldSkipNet(iface)) continue;
                    String[] rest = line.substring(colon + 1).trim().split("\\s+");
                    if (rest.length < 16) continue;
                    long rxBytes = Long.parseLong(rest[0]);
                    long txBytes = Long.parseLong(rest[8]);
                    long rxPackets = Long.parseLong(rest[1]);
                    long txPackets = Long.parseLong(rest[9]);
                    long rxErrors = Long.parseLong(rest[2]) + Long.parseLong(rest[3]);
                    long txErrors = Long.parseLong(rest[10]) + Long.parseLong(rest[11]);
                    out.put(iface, new long[]{rxBytes, txBytes, rxPackets, txPackets, rxErrors, txErrors});
                }
            } catch (Throwable ignore) {
            }
            return out;
        }

        private static boolean shouldSkipNet(String iface) {
            for (String s : SKIP_NET_IFACES) {
                if (s.equals(iface)) return true;
            }
            return false;
        }

        private record Snapshot(Map<String, long[]> disks, Map<String, long[]> net,
                                java.util.Set<String> interfaces,
                                long at, boolean valid) {}
    }
}
