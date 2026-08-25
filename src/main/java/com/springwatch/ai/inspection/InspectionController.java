package com.springwatch.ai.inspection;

import com.springwatch.model.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ai/inspection")
@RequiredArgsConstructor
public class InspectionController {

    private final HealthInspectionService inspectionService;

    /**
     * 手动触发一次全应用巡检。
     * POST /api/ai/inspection/run
     */
    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run() {
        log.info("[kxj: 手动巡检触发");
        String report = inspectionService.inspectAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("len", report.length());
        out.put("report", report);
        return ApiResponse.ok(out);
    }
}