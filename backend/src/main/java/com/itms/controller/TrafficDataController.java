package com.itms.controller;

import com.itms.common.Result;
import com.itms.entity.TrafficData;
import com.itms.service.TrafficDataService;
import com.itms.service.TrafficSimulatorService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/traffic")
@RequiredArgsConstructor
@Tag(name = "实时交通数据")
public class TrafficDataController {

    private final TrafficDataService trafficDataService;
    private final TrafficSimulatorService simulatorService;

    @GetMapping("/realtime")
    public Result<List<Map<String, Object>>> getRealTimeData() {
        return Result.success(trafficDataService.getRealTimeData());
    }

    @GetMapping("/summary")
    public Result<Map<String, Object>> getTrafficSummary() {
        return Result.success(trafficDataService.getTrafficSummary());
    }

    @GetMapping("/history")
    public Result<List<TrafficData>> getHistoryData(
            @RequestParam String intersectionId,
            @RequestParam(required = false) Integer minutes,
            @RequestParam(required = false) Integer limit) {
        if (limit != null) {
            return Result.success(trafficDataService.getHistoryData(intersectionId, limit));
        }
        return Result.success(trafficDataService.getHistoryData(intersectionId, minutes == null ? 60 : minutes));
    }

    @GetMapping("/heatmap")
    public Result<List<Map<String, Object>>> getHeatmapData() {
        return Result.success(trafficDataService.getHeatmapData());
    }

    @GetMapping("/intersection-summary")
    public Result<List<Map<String, Object>>> getIntersectionSummary() {
        return Result.success(trafficDataService.getIntersectionSummary());
    }

    @PostMapping("/save")
    public Result<TrafficData> saveTrafficData(@RequestBody TrafficData data) {
        return Result.success("数据保存成功", trafficDataService.saveTrafficData(data));
    }

    @PostMapping("/batch-save")
    public Result<List<TrafficData>> batchSave(@RequestBody List<TrafficData> dataList) {
        return Result.success("批量保存成功", trafficDataService.batchSave(dataList));
    }

    @PostMapping("/simulator/start")
    public Result<Boolean> startSimulator() {
        simulatorService.start();
        return Result.success("模拟器已启动", simulatorService.isRunning());
    }

    @PostMapping("/simulator/stop")
    public Result<Boolean> stopSimulator() {
        simulatorService.stop();
        return Result.success("模拟器已停止", simulatorService.isRunning());
    }

    @PostMapping("/simulator/generate-once")
    public Result<List<TrafficData>> generateOnce() {
        return Result.success("已生成一批交通数据", simulatorService.generateOnce());
    }
}
