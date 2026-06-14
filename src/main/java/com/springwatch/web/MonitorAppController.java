package com.springwatch.web;

import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.dto.AppRegisterRequest;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.service.MonitorAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/apps")
@RequiredArgsConstructor
public class MonitorAppController {

    private final MonitorAppService monitorAppService;


}