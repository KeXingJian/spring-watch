package com.springwatch.agent.boot;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bootstrap-isolated JDBC 事件存储。
 * <p>
 * 为什么 bootstrap:java.sql.* 由 bootstrap classloader 拥有,advice 内联
 * 字节码引用 {@code JdbcStorage} 时,JdbcStorage 必须同时被 bootstrap 看
 * 见,否则 {@link NoClassDefFoundError}。
 * <p>
 * 写入路径(在 java.sql.Statement / PreparedStatement advice 内):
 * <pre>
 *   onEnter: ACTIVATION.set(new Entry(now, thread))
 *   onExit : ACTIVATION.get().set(...) ; QUEUE.offer(entry)
 * </pre>
 * 读出路径(在 agent 进程内,JdbcEventExporter 轮询):
 * <pre>
 *   drain(max) -&gt; Object[] {sql, thread, durationNanos, errorType} * n
 * </pre>
 *
 * <p>借鉴 OTel {@code JdbcSingletons} 的 bootstrap 注入思路,本类保持极简,只
 * 做 record-and-forward,不做 digest / 指标入库(留给 agent 进程)。
 */
public final class JdbcStorage {

    public static final int CAPACITY = 16384;

    private static final Deque<Entry> QUEUE = new ArrayDeque<>(4096);
    private static final ThreadLocal<Entry> ACTIVATION = new ThreadLocal<>();

    private JdbcStorage() {
    }

    public static void onStart(String threadName, long startNanos) {
        Entry e = ACTIVATION.get();
        if (e == null) {
            e = new Entry();
            ACTIVATION.set(e);
        }
        e.threadName = threadName;
        e.startNanos = startNanos;
        e.sql = null;
        e.errorType = null;
    }

    public static void onEnd(String sql, Throwable thrown, long endNanos) {
        Entry e = ACTIVATION.get();
        if (e == null) {
            return;
        }
        ACTIVATION.remove();
        e.sql = sql;
        e.durationNanos = endNanos - e.startNanos;
        e.errorType = thrown == null ? null : thrown.getClass().getName();
        synchronized (QUEUE) {
            while (QUEUE.size() >= CAPACITY) {
                QUEUE.pollFirst();
            }
            QUEUE.offerLast(e);
        }
    }

    /**
     * 批量拉取(flat object array,避免 agent 端持有 Entry 类引用)。
     * <p>
     * 返回数组长度 = n * 4,每 4 元素依次为:sql, threadName, durationNanos, errorType。
     */
    public static Object[] drain(int max) {
        synchronized (QUEUE) {
            int n = Math.min(max, QUEUE.size());
            if (n == 0) return EMPTY;
            Object[] result = new Object[n * 4];
            for (int i = 0; i < n; i++) {
                Entry e = QUEUE.pollFirst();
                result[i * 4] = e.sql;
                result[i * 4 + 1] = e.threadName;
                result[i * 4 + 2] = e.durationNanos;
                result[i * 4 + 3] = e.errorType;
            }
            return result;
        }
    }

    public static int size() {
        synchronized (QUEUE) {
            return QUEUE.size();
        }
    }

    public static void clear() {
        synchronized (QUEUE) {
            QUEUE.clear();
        }
    }

    private static final Object[] EMPTY = new Object[0];

    /**
     * bootstrap 域内不可引用任何 agent 类,只允许 JDK 类型。
     */
    public static final class Entry {
        public String threadName;
        public String sql;
        public long startNanos;
        public long durationNanos;
        public String errorType;
    }
}
