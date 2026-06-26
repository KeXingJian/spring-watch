package com.mock.test.sim;

import com.mock.test.service.CartService;
import com.mock.test.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@ConditionalOnProperty(name = "mock.sim.enabled", havingValue = "true", matchIfMissing = true)
public class BusinessEventSimulator extends BaseSimulator {

    private static final int[] USER_IDS = {1, 2, 3};
    private static final int[] PRODUCT_IDS = {1, 2, 3, 4, 5};

    private final OrderService orderService;
    private final CartService cartService;
    private final JdbcTemplate jdbcTemplate;

    @Value("${mock.sim.business.interval-ms:3000}")
    private long intervalMs;

    @Value("${mock.sim.business.error-rate:0.15}")
    private double errorRate;

    public BusinessEventSimulator(OrderService orderService, CartService cartService, JdbcTemplate jdbcTemplate) {
        super("business-event", "biz");
        this.orderService = orderService;
        this.cartService = cartService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected long resolveIntervalMs() {
        return intervalMs;
    }

    @Override
    protected void tick() {
        double errR = ThreadLocalRandom.current().nextDouble();
        if (errR < errorRate) {
            doAnomaly();
            return;
        }
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 35) {
            doCreate();
        } else if (roll < 60) {
            doPay();
        } else if (roll < 75) {
            doShip();
        } else if (roll < 85) {
            doComplete();
        } else if (roll < 90) {
            doCancel();
        } else {
            doAddCart();
        }
    }

    private void doCreate() {
        long userId = USER_IDS[ThreadLocalRandom.current().nextInt(USER_IDS.length)];
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        List<Map<String, Object>> items = new ArrayList<>();
        int n = 1 + ThreadLocalRandom.current().nextInt(3);
        for (int i = 0; i < n; i++) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("productId", PRODUCT_IDS[ThreadLocalRandom.current().nextInt(PRODUCT_IDS.length)]);
            it.put("quantity", 1 + ThreadLocalRandom.current().nextInt(3));
            items.add(it);
        }
        body.put("items", items);
        Map<String, Object> r = orderService.createOrder(body);
        log.info("[kxj: 业务事件] 创建订单 userId={} items={} result={}", userId, items.size(),
                r.containsKey("error") ? "ERR:" + r.get("error") : "ok");
    }

    private void doPay() {
        Long id = pickOrderByStatus("created");
        if (id == null) {
            doCreate();
            return;
        }
        Map<String, Object> r = orderService.payOrder(id);
        log.info("[kxj: 业务事件] 支付订单 id={} result={}", id,
                r != null && !r.containsKey("error") ? "ok" : "skip");
    }

    private void doShip() {
        Long id = pickOrderByStatus("paid");
        if (id == null) {
            doPay();
            return;
        }
        Map<String, Object> r = orderService.shipOrder(id);
        log.info("[kxj: 业务事件] 发货订单 id={} result={}", id,
                r != null && !r.containsKey("error") ? "ok" : "skip");
    }

    private void doComplete() {
        Long id = pickOrderByStatus("shipped");
        if (id == null) {
            doShip();
            return;
        }
        Map<String, Object> r = orderService.completeOrder(id);
        log.info("[kxj: 业务事件] 完成订单 id={} result={}", id,
                r != null && !r.containsKey("error") ? "ok" : "skip");
    }

    private void doCancel() {
        Long id = pickOrderByStatus("created");
        if (id == null) {
            id = pickOrderByStatus("paid");
        }
        if (id == null) {
            doCreate();
            return;
        }
        Map<String, Object> r = orderService.cancelOrder(id);
        log.info("[kxj: 业务事件] 取消订单 id={} result={}", id,
                r != null && !r.containsKey("error") ? "ok" : "skip");
    }

    private void doAddCart() {
        long userId = USER_IDS[ThreadLocalRandom.current().nextInt(USER_IDS.length)];
        long productId = PRODUCT_IDS[ThreadLocalRandom.current().nextInt(PRODUCT_IDS.length)];
        int qty = 1 + ThreadLocalRandom.current().nextInt(5);
        Map<String, Object> r = cartService.addToCart(userId, productId, qty);
        log.info("[kxj: 业务事件] 加购物车 userId={} productId={} qty={} result={}",
                userId, productId, qty, r != null ? "ok" : "not-found");
    }

    private void doAnomaly() {
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.5) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", USER_IDS[ThreadLocalRandom.current().nextInt(USER_IDS.length)]);
            List<Map<String, Object>> items = new ArrayList<>();
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("productId", 9999);
            it.put("quantity", 1);
            items.add(it);
            body.put("items", items);
            Map<String, Object> res = orderService.createOrder(body);
            log.warn("[kxj: 业务事件-异常路径] 商品不存在 productId=9999 result={}",
                    res.containsKey("error") ? res.get("error") : "unexpected-ok");
        } else if (r < 0.85) {
            Map<String, Object> res = orderService.payOrder(9999L);
            log.warn("[kxj: 业务事件-异常路径] 支付不存在订单 id=9999 result={}",
                    res != null && res.containsKey("error") ? res.get("error") : "unexpected");
        } else {
            long userId = 9999L;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", userId);
            body.put("items", List.of());
            Map<String, Object> res = orderService.createOrder(body);
            log.warn("[kxj: 业务事件-异常路径] 用户不存在 userId=9999 result={}",
                    res.containsKey("error") ? res.get("error") : "unexpected-ok");
        }
    }

    private Long pickOrderByStatus(String status) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM orders WHERE status = ? ORDER BY id DESC LIMIT 1", status);
            if (rows.isEmpty()) return null;
            return ((Number) rows.get(0).get("ID")).longValue();
        } catch (Exception e) {
            return null;
        }
    }
}
