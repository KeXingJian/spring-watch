package com.mock.test.metrics;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class BusinessMetrics {

    private final LongCounter orderCreatedCounter;
    private final LongCounter orderPaidCounter;
    private final DoubleHistogram orderAmountHistogram;
    private final LongCounter userLoginCounter;

    public BusinessMetrics() {
        Meter meter = GlobalOpenTelemetry.get().getMeter("com.mock.test");

        this.orderCreatedCounter = meter.counterBuilder("business.order.created")
                .setDescription("订单创建次数")
                .setUnit("{order}")
                .build();

        this.orderPaidCounter = meter.counterBuilder("business.order.paid")
                .setDescription("订单支付次数")
                .setUnit("{order}")
                .build();

        this.orderAmountHistogram = meter.histogramBuilder("business.order.amount")
                .setDescription("订单金额分布")
                .setUnit("CNY")
                .build();

        this.userLoginCounter = meter.counterBuilder("business.user.login")
                .setDescription("用户登录次数")
                .setUnit("{login}")
                .build();
    }

    public void recordOrderCreated(String status, double amount) {
        orderCreatedCounter.add(1, Attributes.of(AttributeKey.stringKey("status"), status));
        orderAmountHistogram.record(amount);
    }

    public void recordOrderPaid(double amount) {
        orderPaidCounter.add(1);
        orderAmountHistogram.record(amount);
    }

    public void recordUserLogin(String channel) {
        userLoginCounter.add(1, Attributes.of(AttributeKey.stringKey("channel"), channel));
    }
}
