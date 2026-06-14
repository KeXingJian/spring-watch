package com.springwatch.web;

import com.springwatch.model.dto.ApiResponse;
import com.springwatch.service.LogQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogQueryService logQueryService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

}