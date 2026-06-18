package com.springwatch.web;

import com.springwatch.alerter.AlertNotifier;
import com.springwatch.service.NotificationConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationConfigService configService;
    private final AlertNotifier alertNotifier;


}
