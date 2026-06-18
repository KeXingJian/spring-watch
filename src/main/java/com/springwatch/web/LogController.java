package com.springwatch.web;

import com.springwatch.analysis.LogAggregator;
import com.springwatch.analysis.LogAnomalyDetector;
import com.springwatch.analysis.LogMetricsLinker;
import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.entity.LogDedupCount;
import com.springwatch.repository.LogDedupCountRepository;
import com.springwatch.service.LogQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogQueryService logQueryService;
    private final LogAggregator logAggregator;
    private final LogAnomalyDetector anomalyDetector;
    private final LogMetricsLinker metricsLinker;
    private final LogDedupCountRepository dedupCountRepository;


}
