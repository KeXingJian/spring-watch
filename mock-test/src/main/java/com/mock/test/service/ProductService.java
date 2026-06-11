package com.mock.test.service;

import com.mock.test.dao.ProductDao;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductService {

    private final ProductDao productDao;

    public ProductService(ProductDao productDao) {
        this.productDao = productDao;
    }

    @WithSpan
    public Map<String, Object> listProducts(int page, int size, String category) {
        List<Map<String, Object>> list;
        if (category != null && !category.isBlank()) {
            list = productDao.findByCategory(category);
        } else {
            list = productDao.findAll();
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
    public Map<String, Object> getProduct(Long id) {
        return productDao.findById(id);
    }

    @WithSpan
    public Map<String, Object> createProduct(Map<String, Object> body) {
        return productDao.save(
                (String) body.getOrDefault("name", "商品"),
                (String) body.getOrDefault("category", "其他"),
                ((Number) body.getOrDefault("price", 0.0)).doubleValue(),
                ((Number) body.getOrDefault("stock", 0)).intValue()
        );
    }

    @WithSpan
    public long count() {
        return productDao.count();
    }

    @WithSpan
    public Map<String, Object> findLowStock(int threshold) {
        List<Map<String, Object>> low = productDao.findLowStock(threshold);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map<String, Object> p : low) {
            result.put(p.get("name").toString(), p.get("stock"));
        }
        return result;
    }
}
