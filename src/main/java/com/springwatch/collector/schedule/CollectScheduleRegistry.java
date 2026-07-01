package com.springwatch.collector.schedule;

import com.springwatch.collector.AppPullTask;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.MonitorAppRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class CollectScheduleRegistry implements ApplicationRunner {

    private final MonitorAppRepository repository;
    private final AppPullTask appPullTask;
    private final AppScheduleProperties properties;
    private final MeterRegistry meterRegistry;

    private ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AppSchedule> activeSchedules = new ConcurrentHashMap<>();
    private Timer startupRegisterTimer;

    @PostConstruct
    void init() {
        ThreadFactory tf = Thread.ofVirtual().name("collect-sched-", 0).factory();
        this.scheduler = Executors.newScheduledThreadPool(properties.getPoolSize(), tf);
        this.startupRegisterTimer = Timer.builder("spring.watch.collector.startup.register")
                .description("启动加载全部应用注册耗时(防雷鸣群羊效果验证)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        meterRegistry.gauge("spring.watch.collector.startup.jobs", jobs, ConcurrentHashMap::size);
        log.info("[kxj: 调度器初始化 - poolSize={}, perHostConcurrent={}, jitterPercent={}, threadFactory=virtualThread, antiHerd=appidHash+interval分散]",
                properties.getPoolSize(), properties.getPerHostConcurrent(), properties.getJitterPercent());
    }

    @PreDestroy
    void shutdown() {
        log.info("[spring-watch: 调度器关闭开始 - activeJobs={}]", jobs.size());
        jobs.values().forEach(f -> f.cancel(false));
        jobs.clear();
        activeSchedules.clear();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("[spring-watch: 调度器未在5s内优雅关闭 - 强制 shutdownNow]");
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("[spring-watch: 调度器关闭完成]");
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[kxj: 启动加载全部应用开始 - 首次延迟采用 appid hash 分摊,防雷鸣群羊]");
        List<MonitorApp> allApps = repository.findAll();
        log.info("[spring-watch: 启动加载全部应用 - dbCount={}]", allApps.size());
        long startNs = System.nanoTime();
        allApps.forEach(this::upsert);
        long costMs = (System.nanoTime() - startNs) / 1_000_000L;
        startupRegisterTimer.record(Duration.ofMillis(costMs));
        log.info("[kxj: 启动加载完成 - 已注册任务数={}/{}, 注册耗时={}ms, 首次拉取分摊到 0~intervalMs 区间, 避免雷鸣群羊]",
                allApps.size(), allApps.size(), costMs);
    }

    public synchronized void upsert(MonitorApp app) {
        if (app == null || app.getAppid() == null) {
            log.warn("[spring-watch: upsert跳过 - app为空或appid为空, id={}]", app == null ? null : app.getId());
            return;
        }
        if (app.getAppid() <= 0L) {
            log.info("[spring-watch: upsert跳过 - appid={} 是 sentinel/infra 标记, 不注册采集任务, app={}",
                    app.getAppid(), app.getAppName());
            return;
        }
        log.info("[spring-watch: upsert开始 - appid={}, app={}, status={}, scheduleType={}, scrapeInterval={}, cron={}]",
                app.getAppid(), app.getAppName(), app.getStatus(),
                app.getScheduleType(), app.getScrapeInterval(), app.getCronExpression());

        cancelInternal(app.getAppid());

        AppSchedule schedule = AppSchedule.from(app, properties.getJitterPercent());
        activeSchedules.put(app.getAppid(), schedule);

        long initialDelay = schedule.firstDelayMs(Instant.now());
        ScheduledFuture<?> future;
        if (schedule.type() == AppScheduleType.CRON) {
            future = scheduler.schedule(
                    () -> cronLoop(app.getAppid()),
                    initialDelay,
                    TimeUnit.MILLISECONDS);
            log.info("[spring-watch: 注册CRON任务 - appid={}, app={}, status={}, initialDelayMs={}, cron={}]",
                    app.getAppid(), app.getAppName(), app.getStatus(), initialDelay, app.getCronExpression());
        } else {
            future = scheduler.scheduleWithFixedDelay(
                    () -> safeRun(app.getAppid()),
                    initialDelay,
                    schedule.baseIntervalMs(),
                    TimeUnit.MILLISECONDS);
            log.info("[kxj: 注册INTERVAL任务(防雷鸣群羊) - appid={}, app={}, status={}, initialDelayMs={}/{}ms, periodMs={}, jitterPercent={}%, hash桶分散]",
                    app.getAppid(), app.getAppName(), app.getStatus(), initialDelay,
                    schedule.baseIntervalMs(), schedule.baseIntervalMs(),
                    properties.getJitterPercent());
        }
        jobs.put(app.getAppid(), future);
        log.info("[spring-watch: upsert完成 - appid={}, activeJobs={}]", app.getAppid(), jobs.size());
    }

    public void cancel(Long appid) {
        if (appid == null) {
            log.warn("[spring-watch: cancel跳过 - appid为空]");
            return;
        }
        log.info("[spring-watch: cancel开始 - appid={}]", appid);
        cancelInternal(appid);
        log.info("[spring-watch: cancel完成 - appid={}, activeJobs={}]", appid, jobs.size());
    }

    private void cancelInternal(Long appid) {
        ScheduledFuture<?> old = jobs.remove(appid);
        activeSchedules.remove(appid);
        if (old != null) {
            old.cancel(false);
            log.debug("[spring-watch: 取消旧future - appid={}, wasDone={}, wasCancelled={}]",
                    appid, old.isDone(), old.isCancelled());
        } else {
            log.trace("[spring-watch: 无旧future需要取消 - appid={}]", appid);
        }
    }


    private void cronLoop(Long appid) {
        AppSchedule schedule = activeSchedules.get(appid);
        if (schedule == null || schedule.type() != AppScheduleType.CRON) {
            log.debug("[spring-watch: cronLoop退出 - appid={}, schedule={}](非cron或已移除)", appid, schedule);
            return;
        }
        ScheduledFuture<?> current = jobs.get(appid);
        if (current == null || current.isCancelled()) {
            log.info("[spring-watch: cronLoop退出 - appid={}, future已取消]", appid);
            return;
        }
        log.info("[spring-watch: cron触发执行 - appid={}]", appid);
        safeRun(appid);

        if (!jobs.containsKey(appid)) {
            log.info("[spring-watch: cronLoop终止 - appid={}, 执行期间被移除]", appid);
            return;
        }
        AppSchedule latest = activeSchedules.get(appid);
        if (latest == null) {
            log.info("[spring-watch: cronLoop终止 - appid={}, schedule已清空]", appid);
            return;
        }
        long nextDelay = latest.nextIntervalMs(Instant.now());
        ScheduledFuture<?> next = scheduler.schedule(
                () -> cronLoop(appid),
                nextDelay,
                TimeUnit.MILLISECONDS);
        ScheduledFuture<?> prev = jobs.put(appid, next);
        if (prev != null && prev != current && !prev.isDone()) {
            log.warn("[spring-watch: cronLoop清理残留future - appid={}]", appid);
            prev.cancel(false);
        }
        log.debug("[spring-watch: cronLoop排下一次 - appid={}, nextDelayMs={}]", appid, nextDelay);
    }

    private void safeRun(Long appid) {
        long start = System.nanoTime();
        log.debug("[spring-watch: safeRun开始 - appid={}]", appid);
        try {
            appPullTask.run(appid);
        } catch (Throwable t) {
            log.warn("[spring-watch: 采集执行异常 - appid={}, error={}]", appid, t.getMessage(), t);
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            log.debug("[spring-watch: safeRun结束 - appid={}, costMs={}]", appid, costMs);
        }
    }
}
