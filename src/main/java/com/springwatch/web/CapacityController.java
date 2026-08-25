package com.springwatch.web;

import com.springwatch.ai.predict.CapacityPredictionService;
import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.entity.CapacityPrediction;
import com.springwatch.repository.CapacityPredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/capacity")
@RequiredArgsConstructor
public class CapacityController {

    private final CapacityPredictionService predictionService;
    private final CapacityPredictionRepository predictionRepository;

    /**
     * 触发一次容量预测并落库。
     * GET /api/capacity/predict?appid=1&metric=jvm_memory_used_bytes&horizon=24
     */
    @GetMapping("/predict")
    public ApiResponse<Map<String, Object>> predict(
            @RequestParam Long appid,
            @RequestParam String metric,
            @RequestParam(defaultValue = "24") int horizon) {
        log.info("[kxj: 容量预测请求 - appid={}, metric={}, horizon={}h]", appid, metric, horizon);
        int safeHorizon = Math.min(Math.max(horizon, 1), 168);
        CapacityPredictionService.PredictionResult r = predictionService.runAndPersist(appid, metric, safeHorizon);
        if (r == null) {
            return ApiResponse.fail(404, "数据不足,无法预测: appid=" + appid + ", metric=" + metric);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appid", appid);
        out.put("metric", r.metric());
        out.put("current", r.current());
        out.put("predicted", r.predicted());
        out.put("slope", r.slope());
        out.put("confidence", r.confidence());
        out.put("scenario", r.scenario());
        out.put("risk", r.risk());
        out.put("horizonHours", r.horizonHours());
        out.put("explanation", r.explanation());
        return ApiResponse.ok(out);
    }

    /**
     * 查询历史预测记录。
     * GET /api/capacity/history?appid=1&page=0&size=10
     */
    @GetMapping("/history")
    public ApiResponse<Page<CapacityPrediction>> history(
            @RequestParam("appid") Long appid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by("createdAt").descending());
        return ApiResponse.ok(predictionRepository.findByAppidOrderByCreatedAtDesc(appid, pageable));
    }
}
