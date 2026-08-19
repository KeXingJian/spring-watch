package com.springwatch.sdk.metric;

/**
 * 客户可选的手动埋点 API。
 * <p>
 * 当自动织入(基于 @SwMon / @WithSpan)不够用时,业务代码可调用本接口
 * 上报自定义指标。所有方法在 Agent 未挂载时均为 no-op,不会抛异常。
 */
public final class SwMetricsRecorder {

    private static volatile MetricsBackend BACKEND = MetricsBackend.NOOP;

    private SwMetricsRecorder() {
    }

    public static void register(MetricsBackend backend) {
        BACKEND = backend == null ? MetricsBackend.NOOP : backend;
    }

    public static void unregister() {
        BACKEND = MetricsBackend.NOOP;
    }

    public static void counterInc(String name, String... labelPairs) {
        try {
            BACKEND.counterInc(name, labelPairs);
        } catch (Throwable ignore) {
        }
    }

    public static void histogramObserve(String name, double value, String... labelPairs) {
        try {
            BACKEND.histogramObserve(name, value, labelPairs);
        } catch (Throwable ignore) {
        }
    }

    public static void gaugeSet(String name, double value, String... labelPairs) {
        try {
            BACKEND.gaugeSet(name, value, labelPairs);
        } catch (Throwable ignore) {
        }
    }

    public interface MetricsBackend {
        MetricsBackend NOOP = new MetricsBackend() {
            @Override
            public void counterInc(String name, String... labelPairs) {
            }

            @Override
            public void histogramObserve(String name, double value, String... labelPairs) {
            }

            @Override
            public void gaugeSet(String name, double value, String... labelPairs) {
            }
        };

        void counterInc(String name, String... labelPairs);

        void histogramObserve(String name, double value, String... labelPairs);

        void gaugeSet(String name, double value, String... labelPairs);
    }
}
