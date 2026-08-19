package com.springwatch.inflight;

import com.sun.management.OperatingSystemMXBean;
import com.springwatch.model.event.LogEvent;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;


import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;


/**
 * 模拟 AgentLogCollector.collect:本地造 LogEvent,直接 bridge.sendLogBatch 投。
 * Spring 装配 inflight 链路,排除 DB/Mail/Flyway 避免连不上外网。
 */
@SpringBootTest(
    classes = com.springwatch.SpringWatchApplication.class
)
class SendBatchConcurrentTest {

    @Autowired
    private InflightProducerBridge inflightProducerBridge;

    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService statsScheduler;

    private final int curr = 100;
    private final int batchSize = 10000;

    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);
    private final AtomicLong totalBatches = new AtomicLong(0);

    private long lastSent = 0;
    private long lastTs = System.nanoTime();

    private void intervalLoop(){
        List<LogEvent> events = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            events.add(LogEvent.builder()
                    .appid(1000L)
                    .level("INFO")
                    .logger("com.springwatch.test.Logger")
                    .threadName(Thread.currentThread().getName())
                    .message("test log message - " + i)
                    .traceId("trace-" + System.nanoTime())
                    .timestamp(Instant.now())
                    .host("127.0.0.1")
                    .service("spring-watch-test")
                    .method("intervalLoop")
                    .env("dev")
                    .fingerprint("fp-" + i)
                    .pattern("test-pattern")
                    .build());
        }

        int accepted = inflightProducerBridge.sendLogBatch(events);
        totalBatches.incrementAndGet();
        if (accepted == events.size()) {
            totalSent.addAndGet(accepted);
        } else {
            totalSent.addAndGet(accepted);
            totalRejected.addAndGet(events.size() - accepted);
        }
        scheduler.schedule(
                this::intervalLoop,
                500,
                TimeUnit.MILLISECONDS);
    }

    private void printStats() {
        long now = System.nanoTime();
        long sent = totalSent.get();
        long rejected = totalRejected.get();
        long batches = totalBatches.get();
        long deltaSent = sent - lastSent;
        double deltaSec = (now - lastTs) / 1_000_000_000.0;
        long tps = deltaSec > 0 ? (long) (deltaSent / deltaSec) : 0;
        lastSent = sent;
        lastTs = now;

        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double procCpu = osBean.getCpuLoad() * 100;
        double sysCpu = osBean.getSystemCpuLoad() * 100;
        long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long totalMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        System.out.printf("[kxj: STAT] sent=%d (tps=%d), rejected=%d, batches=%d, procCpu=%.2f%%, sysCpu=%.2f%%, jvmMem=%d/%dMB (max=%dMB)%n",
                sent, tps, rejected, batches, procCpu, sysCpu, totalMem - freeMem, totalMem, maxMem);
    }

    @Test
    public void test(){
        ThreadFactory tf = Thread.ofVirtual().name("collect-test-", 0).factory();
        scheduler =Executors.newScheduledThreadPool(curr, tf);

        statsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stats-reporter");
            t.setDaemon(true);
            return t;
        });
        statsScheduler.scheduleAtFixedRate(SendBatchConcurrentTest.this::printStats, 1, 2, TimeUnit.SECONDS);


        for (int i = 0; i < curr; i++) {
            scheduler.schedule(
                    this::intervalLoop,
                    100,
                    TimeUnit.MILLISECONDS);
        }
        while (true);
    }

}
