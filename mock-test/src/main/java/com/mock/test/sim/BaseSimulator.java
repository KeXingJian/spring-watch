package com.mock.test.sim;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class BaseSimulator {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final String name;
    private final String threadName;
    private ScheduledExecutorService exec;

    protected BaseSimulator(String name, String threadName) {
        this.name = name;
        this.threadName = threadName;
    }

    @PostConstruct
    void start() {
        long ms = resolveIntervalMs();
        if (ms <= 0) {
            log.info("[kxj: Simulator禁用] name={} reason=interval<=0", name);
            return;
        }
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-" + threadName);
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(this::safeTick, ms, ms, TimeUnit.MILLISECONDS);
        log.info("[kxj: Simulator启动] name={} interval={}ms thread=sim-{}",
                name, ms, threadName);
        onStart();
    }

    @PreDestroy
    void stop() {
        onStop();
        if (exec != null) {
            log.info("[kxj: Simulator关闭] name={}", name);
            exec.shutdownNow();
        }
    }

    protected abstract long resolveIntervalMs();

    protected abstract void tick();

    protected void onStart() {
    }

    protected void onStop() {
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            log.debug("[kxj: Simulator异常] name={} err={}", name, e.getMessage());
        }
    }
}
