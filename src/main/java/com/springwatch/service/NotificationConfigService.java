package com.springwatch.service;

import com.springwatch.repository.AlertNotificationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationConfigService {

    private final AlertNotificationConfigRepository repository;

}
