package com.itms.controller;

import com.itms.common.Result;
import com.itms.entity.TrafficSignal;
import com.itms.service.TrafficSignalService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "信号灯优化")
public class SignalController {

    private final TrafficSignalService signalService;

    @GetMapping({"/api/signal/{intersectionId}", "/api/signals/{intersectionId}/current"})
    public Result<?> getSignals(@PathVariable String intersectionId) {
        return Result.success(signalService.getSignals(intersectionId));
    }

    @GetMapping("/api/signals/{intersectionId}/state")
    public Result<Map<String, Object>> getCurrent(@PathVariable String intersectionId) {
        return Result.success(signalService.getCurrent(intersectionId));
    }

    @PostMapping({"/api/signal/optimize/{intersectionId}", "/api/signals/{intersectionId}/optimize"})
    public Result<Map<String, Object>> optimizeSignals(
            @PathVariable String intersectionId,
            @RequestParam(defaultValue = "BALANCED") String strategy) {
        return Result.success("规则候选方案已生成，待确认应用", signalService.optimizeSignals(intersectionId, strategy));
    }

    @PostMapping({"/api/signal/apply/{intersectionId}", "/api/signals/{intersectionId}/apply"})
    public Result<String> applySignalPlan(@PathVariable String intersectionId) {
        signalService.applySignalPlan(intersectionId);
        return Result.success("模拟配时方案已应用（未连接真实设备）");
    }

    @GetMapping("/api/signal/overview")
    public Result<List<Map<String, Object>>> getSignalOverviewCompat() {
        return Result.success(signalService.getSignalOverview());
    }

    @GetMapping("/api/signals/intersections")
    public Result<List<Map<String, Object>>> getSignalIntersections() {
        return Result.success(signalService.getSignalIntersections());
    }
}
