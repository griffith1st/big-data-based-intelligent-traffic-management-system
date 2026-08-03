package com.itms.controller;

import com.itms.common.Result;
import com.itms.entity.TrafficPrediction;
import com.itms.service.TrafficPredictionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 流量预测控制器
 * 提供30分钟流量预测与拥堵预警
 */
@RestController
@RequestMapping({"/api/prediction", "/api/predictions"})
@RequiredArgsConstructor
@Tag(name = "流量预测", description = "LSTM模型流量预测、拥堵风险预警")
public class PredictionController {

    private final TrafficPredictionService predictionService;

    @GetMapping("/{intersectionId}")
    @Operation(summary = "获取路口预测数据", description = "获取指定路口未来30分钟流量预测")
    public Result<List<TrafficPrediction>> getPredictions(
            @PathVariable String intersectionId) {
        return Result.success(predictionService.getPredictions(intersectionId));
    }

    @GetMapping("/overview")
    @Operation(summary = "获取预测概览", description = "获取所有路口预测概览")
    public Result<List<Map<String, Object>>> getPredictionOverview() {
        return Result.success(predictionService.getPredictionOverview());
    }

    @PostMapping({"/trigger", "/lstm/trigger"})
    @Operation(summary = "触发LSTM预测", description = "触发LSTM模型进行流量预测，返回预测批次数据")
    public Result<Map<String, Object>> triggerPrediction() {
        return Result.success(predictionService.triggerPrediction());
    }

    @GetMapping("/latest")
    @Operation(summary = "获取最新预测批次", description = "获取最新的LSTM预测批次数据")
    public Result<Map<String, Object>> getLatest(
            @RequestParam(required = false) String intersectionId) {
        return Result.success(predictionService.getLatestPredictionBatch(intersectionId));
    }

    @GetMapping("/risk")
    @Operation(summary = "检测拥堵风险", description = "分析预测数据，检测拥堵风险并生成预警")
    public Result<List<Map<String, Object>>> detectRisk() {
        return Result.success(predictionService.detectCongestionRisk());
    }
}
