package com.springwatch.web;


import com.springwatch.service.AlertRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/alert")
@RequiredArgsConstructor
public class AlertController {

    private final AlertRuleService alertRuleService;

}
