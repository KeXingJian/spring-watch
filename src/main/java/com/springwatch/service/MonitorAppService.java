package com.springwatch.service;

import com.springwatch.collector.OtelConfigGenerator;
import com.springwatch.collector.schedule.CollectScheduleRegistry;
import com.springwatch.model.dto.AppRegisterRequest;
import com.springwatch.model.entity.MonitorApp;
import com.springwatch.repository.MonitorAppRepository;
import com.springwatch.util.SnowFlakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorAppService {

    private final MonitorAppRepository monitorAppRepository;
    private final CollectScheduleRegistry collectScheduleRegistry;


}
