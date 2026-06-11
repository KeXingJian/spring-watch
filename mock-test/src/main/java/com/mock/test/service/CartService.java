package com.mock.test.service;

import com.mock.test.dao.ProductDao;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CartService {

    private final ProductDao productDao;
    private final ConcurrentHashMap<Long, Map<String, Object>> carts = new ConcurrentHashMap<>();

    public CartService(ProductDao productDao) {
        this.productDao = productDao;
    }

    @WithSpan
    public Map<String, Object> addToCart(Long userId, Long productId, int quantity) {
        Map<String, Object> prod = productDao.findById(productId);
        if (prod == null) return null;

        Map<String, Object> cart = carts.computeIfAbsent(userId, k -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("userId", k);
            c.put("items", new ArrayList<Map<String, Object>>());
            return c;
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) cart.get("items");
        items.add(Map.of(
                "productId", productId,
                "productName", prod.get("name"),
                "quantity", quantity,
                "price", prod.get("price")
        ));
        return cart;
    }

    @WithSpan
    public Map<String, Object> getCart(Long userId) {
        Map<String, Object> cart = carts.get(userId);
        if (cart != null) return cart;
        return Map.of("userId", userId, "items", List.of());
    }
}
