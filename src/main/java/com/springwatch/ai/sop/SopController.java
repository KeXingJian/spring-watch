package com.springwatch.ai.sop;

import com.springwatch.model.dto.ApiResponse;
import com.springwatch.model.entity.SopSchedule;
import com.springwatch.repository.SopScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/sop")
@RequiredArgsConstructor
public class SopController {

    private final SopEngine sopEngine;
    private final SopScheduleRepository scheduleRepository;

    /**
     * 创建定时巡检任务。
     * POST /api/sop/schedule  body: { sopName, appid?, cronExpression }
     */
    @PostMapping("/schedule")
    public ApiResponse<SopSchedule> schedule(@RequestBody ScheduleRequest req) {
        if (req == null || req.sopName() == null || req.cronExpression() == null) {
            return ApiResponse.fail(400, "sopName 与 cronExpression 必填");
        }
        SopSchedule s = SopSchedule.builder()
                .sopName(req.sopName())
                .appid(req.appid())
                .cronExpression(req.cronExpression())
                .enabled(true)
                .nextRunTime(Instant.now())
                .build();
        SopSchedule saved = scheduleRepository.save(s);
        log.info("[kxj: SOP 定时任务创建 - id={}, sop={}, cron={}, appid={}]",
                saved.getId(), saved.getSopName(), saved.getCronExpression(), saved.getAppid());
        return ApiResponse.ok(saved);
    }

    /**
     * 立即执行一次技能。
     * POST /api/sop/run  body: { sopName, appid? }
     */
    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody RunRequest req) {
        if (req == null || req.sopName() == null) {
            return ApiResponse.fail(400, "sopName 必填");
        }
        log.info("[kxj: SOP 手动执行 - sop={}, appid={}]", req.sopName(), req.appid());
        sopEngine.run(req.sopName(), req.appid());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("sop", req.sopName());
        return ApiResponse.ok(out);
    }

    @GetMapping("/schedules")
    public ApiResponse<Map<String, Object>> listSchedules() {
        List<SopSchedule> rows = scheduleRepository.findAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("rows", rows);
        return ApiResponse.ok(out);
    }

    @DeleteMapping("/schedules/{id}")
    public ApiResponse<Void> deleteSchedule(@PathVariable Long id) {
        scheduleRepository.deleteById(id);
        return ApiResponse.ok(null);
    }

    public record ScheduleRequest(String sopName, Long appid, String cronExpression) {}

    public record RunRequest(String sopName, Long appid) {}
}