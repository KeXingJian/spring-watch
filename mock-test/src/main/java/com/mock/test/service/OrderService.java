package com.mock.test.service;

import com.mock.test.dao.OrderDao;
import com.mock.test.dao.ProductDao;
import com.mock.test.dao.UserDao;
import com.mock.test.metrics.BusinessMetrics;
import com.springwatch.SpringWatch;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class OrderService {

    private final OrderDao orderDao;
    private final UserDao userDao;
    private final ProductDao productDao;
    private final BusinessMetrics businessMetrics;

    public OrderService(OrderDao orderDao, UserDao userDao, ProductDao productDao, BusinessMetrics businessMetrics) {
        this.orderDao = orderDao;
        this.userDao = userDao;
        this.productDao = productDao;
        this.businessMetrics = businessMetrics;
    }

    @WithSpan
    @SpringWatch("order.listOrders")
    public Map<String, Object> listOrders(int page, int size, String status) {
        List<Map<String, Object>> list;
        if (status != null && !status.isBlank()) {
            list = orderDao.findByStatus(status);
        } else {
            list = orderDao.findAll();
        }
        int from = Math.min((page - 1) * size, list.size());
        int to = Math.min(from + size, list.size());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list.subList(from, to));
        data.put("total", list.size());
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    @WithSpan
    public Map<String, Object> getOrder(Long id) {
        return orderDao.findById(id);
    }

    @WithSpan
    @SuppressWarnings("unchecked")
    public Map<String, Object> createOrder(Map<String, Object> body) {
        Long userId = ((Number) body.getOrDefault("userId", 1)).longValue();
        Map<String, Object> user = userDao.findById(userId);
        if (user == null) return Map.of("error", "用户不存在");

        List<Map<String, Object>> items = (List<Map<String, Object>>) body.getOrDefault("items", List.of());
        double total = 0;
        List<Map<String, Object>> orderItems = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Long pid = ((Number) it.get("productId")).longValue();
            Map<String, Object> prod = productDao.findById(pid);
            if (prod == null) return Map.of("error", "商品不存在: " + pid);
            int qty = ((Number) it.getOrDefault("quantity", 1)).intValue();
            double price = ((Number) prod.get("price")).doubleValue();
            total += price * qty;
            orderItems.add(Map.of(
                    "productId", pid,
                    "productName", prod.get("name"),
                    "quantity", qty,
                    "price", price
            ));
        }
        if (orderItems.isEmpty()) return Map.of("error", "订单商品不能为空");

        Map<String, Object> saved = orderDao.save(userId, (String) user.get("username"), total, "created", orderItems);
        businessMetrics.recordOrderCreated("created", total);
        return saved;
    }

    @WithSpan
    public Map<String, Object> payOrder(Long id) {
        Map<String, Object> o = orderDao.findById(id);
        if (o == null) return null;
        if (!"created".equals(o.get("status"))) return Map.of("error", "订单状态不允许支付: " + o.get("status"));
        Map<String, Object> updated = orderDao.updateStatus(id, "paid");
        if (updated != null) {
            businessMetrics.recordOrderPaid(((Number) updated.get("totalAmount")).doubleValue());
        }
        return updated;
    }

    @WithSpan
    public Map<String, Object> shipOrder(Long id) {
        Map<String, Object> o = orderDao.findById(id);
        if (o == null) return null;
        if (!"paid".equals(o.get("status"))) return Map.of("error", "订单未支付，无法发货");
        return orderDao.updateStatus(id, "shipped");
    }

    @WithSpan
    public Map<String, Object> completeOrder(Long id) {
        Map<String, Object> o = orderDao.findById(id);
        if (o == null) return null;
        if (!"shipped".equals(o.get("status"))) return Map.of("error", "订单未发货，无法完成");
        return orderDao.updateStatus(id, "completed");
    }

    @WithSpan
    public Map<String, Object> cancelOrder(Long id) {
        Map<String, Object> o = orderDao.findById(id);
        if (o == null) return null;
        String status = (String) o.get("status");
        if ("completed".equals(status) || "cancelled".equals(status))
            return Map.of("error", "订单已结束，无法取消");
        return orderDao.updateStatus(id, "cancelled");
    }

    @WithSpan
    public Map<String, Object> deleteOrder(Long id) {
        return orderDao.deleteById(id);
    }

    @WithSpan
    public long count() {
        return orderDao.count();
    }

    @WithSpan
    public double sumTotalAmount() {
        return orderDao.sumTotalAmount();
    }

    @WithSpan
    public Map<String, Long> countByStatus() {
        return orderDao.countByStatus();
    }
}