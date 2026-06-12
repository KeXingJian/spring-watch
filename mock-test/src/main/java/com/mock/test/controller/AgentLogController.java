package com.mock.test.controller;

import com.mock.test.logging.InMemoryLogBufferAppender;
import com.mock.test.logging.LogEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentLogController {

    @GetMapping("/logs")
    public List<LogEvent> getLogs(@RequestParam String since) {
        Instant sinceInstant;
        try {
            sinceInstant = Instant.parse(since);
        } catch (Exception e) {
            sinceInstant = Instant.EPOCH;
        }
        return InMemoryLogBufferAppender.drainSince(sinceInstant);
    }
}
