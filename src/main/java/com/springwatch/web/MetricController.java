package com.springwatch.web;

import com.springwatch.model.dto.ApiResponse;
import com.springwatch.service.MetricQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricController {

    private final MetricQueryService metricQueryService;


}