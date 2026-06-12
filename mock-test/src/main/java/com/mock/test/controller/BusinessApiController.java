package com.mock.test.controller;

import com.mock.test.service.CartService;
import com.mock.test.service.OrderService;
import com.mock.test.service.ProductService;
import com.mock.test.service.UserService;
import com.mock.test.service.UserStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Slf4j
public class BusinessApiController {

    private final UserService userService;
    private final ProductService productService;
    private final OrderService orderService;
    private final CartService cartService;
    private final UserStatsService userStatsService;

    public BusinessApiController(UserService userService, ProductService productService,
                                 OrderService orderService, CartService cartService,
                                 UserStatsService userStatsService) {
        this.userService = userService;
        this.productService = productService;
        this.orderService = orderService;
        this.cartService = cartService;
        this.userStatsService = userStatsService;
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 200);
        r.put("message", "success");
        r.put("data", data);
        r.put("timestamp", System.currentTimeMillis());
        return r;
    }

    private Map<String, Object> fail(int code, String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", code);
        r.put("message", msg);
        r.put("timestamp", System.currentTimeMillis());
        return r;
    }

    @GetMapping("/users")
    public Map<String, Object> listUsers(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        return ok(userService.listUsers(page, size));
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> getUser(@PathVariable Long id) {
        Map<String, Object> u = userService.getUser(id);
        return u != null ? ok(u) : fail(404, "用户不存在: " + id);
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, Object> body) {
        return ok(userService.createUser(body));
    }

    @GetMapping("/products")
    public Map<String, Object> listProducts(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size,
                                            @RequestParam(required = false) String category) {
        return ok(productService.listProducts(page, size, category));
    }

    @GetMapping("/products/{id}")
    public Map<String, Object> getProduct(@PathVariable Long id) {
        Map<String, Object> p = productService.getProduct(id);
        return p != null ? ok(p) : fail(404, "商品不存在: " + id);
    }

    @PostMapping("/products")
    public Map<String, Object> createProduct(@RequestBody Map<String, Object> body) {
        return ok(productService.createProduct(body));
    }

    @GetMapping("/orders")
    public Map<String, Object> listOrders(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "10") int size,
                                          @RequestParam(required = false) String status) {
        return ok(orderService.listOrders(page, size, status));
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> getOrder(@PathVariable Long id) {
        Map<String, Object> o = orderService.getOrder(id);
        return o != null ? ok(o) : fail(404, "订单不存在: " + id);
    }

    @PostMapping("/orders")
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = orderService.createOrder(body);
        if (result == null) return fail(400, "用户不存在");
        if (result.containsKey("error")) return fail(400, (String) result.get("error"));
        return ok(result);
    }

    @PutMapping("/orders/{id}/pay")
    public Map<String, Object> payOrder(@PathVariable Long id) {
        Map<String, Object> result = orderService.payOrder(id);
        if (result == null) return fail(404, "订单不存在");
        if (result.containsKey("error")) return fail(400, (String) result.get("error"));
        return ok(result);
    }

    @PutMapping("/orders/{id}/ship")
    public Map<String, Object> shipOrder(@PathVariable Long id) {
        Map<String, Object> result = orderService.shipOrder(id);
        if (result == null) return fail(404, "订单不存在");
        if (result.containsKey("error")) return fail(400, (String) result.get("error"));
        return ok(result);
    }

    @PutMapping("/orders/{id}/complete")
    public Map<String, Object> completeOrder(@PathVariable Long id) {
        Map<String, Object> result = orderService.completeOrder(id);
        if (result == null) return fail(404, "订单不存在");
        if (result.containsKey("error")) return fail(400, (String) result.get("error"));
        return ok(result);
    }

    @PutMapping("/orders/{id}/cancel")
    public Map<String, Object> cancelOrder(@PathVariable Long id) {
        Map<String, Object> result = orderService.cancelOrder(id);
        if (result == null) return fail(404, "订单不存在");
        if (result.containsKey("error")) return fail(400, (String) result.get("error"));
        return ok(result);
    }

    @DeleteMapping("/orders/{id}")
    public Map<String, Object> deleteOrder(@PathVariable Long id) {
        Map<String, Object> removed = orderService.deleteOrder(id);
        return removed != null ? ok("订单已删除") : fail(404, "订单不存在");
    }

    @PostMapping("/cart")
    public Map<String, Object> addToCart(@RequestBody Map<String, Object> body) {
        Long userId = ((Number) body.getOrDefault("userId", 1)).longValue();
        Long productId = ((Number) body.getOrDefault("productId", 1)).longValue();
        int quantity = ((Number) body.getOrDefault("quantity", 1)).intValue();
        Map<String, Object> result = cartService.addToCart(userId, productId, quantity);
        if (result == null) return fail(404, "商品不存在");
        return ok(result);
    }

    @GetMapping("/cart/{userId}")
    public Map<String, Object> getCart(@PathVariable Long userId) {
        return ok(cartService.getCart(userId));
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return ok(userStatsService.summary());
    }

    @GetMapping("/users/{id}/stats")
    public Map<String, Object> userStats(@PathVariable Long id) {
        return ok(userStatsService.userOrderSummary(id));
    }

    @GetMapping("/delay/{millis}")
    public Map<String, Object> simulateDelay(@PathVariable long millis) {
        long start = System.currentTimeMillis();
        log.info("simulateDelay called, requested={}ms", millis);
        try {
            Thread.sleep(Math.min(millis, 30000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long actual = System.currentTimeMillis() - start;
        log.info("simulateDelay completed, actual={}ms", actual);
        return ok(Map.of("requestedDelay", millis, "actualDelay", actual));
    }

    @GetMapping("/error/{code}")
    public ResponseEntity<Map<String, Object>> simulateError(@PathVariable int code) {
        return ResponseEntity.status(code).body(fail(code, "模拟 HTTP " + code + " 错误响应"));
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        log.info("ping called at {}", LocalDateTime.now());
        return ok(Map.of("message", "pong", "time", LocalDateTime.now().toString()));
    }
}
