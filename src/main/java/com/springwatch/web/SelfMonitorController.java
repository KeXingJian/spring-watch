package com.springwatch.web;

import com.springwatch.monitor.SelfMonitorCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/self")
@RequiredArgsConstructor
public class SelfMonitorController {

    private final SelfMonitorCollector collector;

    @GetMapping("/realtime")
    public Map<String, Object> realtime() {
        SelfMonitorCollector.Sample s = collector.latest();
        if (s == null) {
            return Map.of("ready", false, "size", collector.size());
        }
        return Map.of("ready", true, "size", collector.size(), "sample", s);
    }

    @GetMapping("/timeseries")
    public Map<String, Object> timeseries(@RequestParam(defaultValue = "60") int window) {
        List<SelfMonitorCollector.Sample> samples = collector.window(window);
        return Map.of("size", samples.size(), "window", window, "samples", samples);
    }
}
