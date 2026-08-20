package com.mock.test.metrics;

import com.springwatch.sdk.metric.SwMetricsRecorder;
import org.springframework.stereotype.Component;

/**
 * 业务指标埋点,基于自研 SDK {@link SwMetricsRecorder} 手动上报。
 * <p>
 * 数据最终汇入 spring-watch-agent 的 MetricRegistry(agent 启动时注册后端),
 * 由 GET /metrics 拉取;Agent 未挂载时 SwMetricsRecorder 自动降级为 NOOP,业务不受影响。
 */
@Component
public class BusinessMetrics {

    private static final String ORDER_CREATED = "business.order.created";
    private static final String ORDER_PAID = "business.order.paid";
    private static final String ORDER_AMOUNT = "business.order.amount";
    private static final String USER_LOGIN = "business.user.login";

    public void recordOrderCreated(String status, double amount) {
        SwMetricsRecorder.counterInc(ORDER_CREATED, "status", status);
        SwMetricsRecorder.histogramObserve(ORDER_AMOUNT, amount);
    }

    public void recordOrderPaid(double amount) {
        SwMetricsRecorder.counterInc(ORDER_PAID);
        SwMetricsRecorder.histogramObserve(ORDER_AMOUNT, amount);
    }

    public void recordUserLogin(String channel) {
        SwMetricsRecorder.counterInc(USER_LOGIN, "channel", channel);
    }
}
