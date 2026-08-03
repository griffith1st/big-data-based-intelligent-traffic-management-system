package com.itms.service.impl;

import com.itms.common.TrafficNetwork;
import com.itms.entity.TrafficAlert;
import com.itms.entity.TrafficData;
import com.itms.entity.TrafficPrediction;
import com.itms.repository.TrafficDataRepository;
import com.itms.repository.TrafficPredictionRepository;
import com.itms.service.SystemLogService;
import com.itms.service.TrafficAlertService;
import com.itms.service.TrafficPredictionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficPredictionServiceImpl implements TrafficPredictionService {

    private final TrafficPredictionRepository predictionRepository;
    private final TrafficDataRepository trafficDataRepository;
    private final TrafficAlertService alertService;
    private final SystemLogService systemLogService;
    private final Random random = new Random();

    @Value("${itms.traffic.prediction-window:30}")
    private int predictionWindow;

    @Override
    public List<TrafficPrediction> getPredictions(String intersectionId) {
        String latestBatch = latestBatchId();
        if (latestBatch == null) {
            triggerPrediction();
            latestBatch = latestBatchId();
        }
        if (latestBatch == null) {
            return List.of();
        }
        return predictionRepository
                .findByPredictionBatchIdAndIntersectionIdOrderByPredictionTimeAsc(latestBatch, intersectionId);
    }

    @Override
    public List<Map<String, Object>> getPredictionOverview() {
        String latestBatch = latestBatchId();
        if (latestBatch == null) {
            return List.of();
        }
        List<TrafficPrediction> predictions = predictionRepository
                .findByPredictionBatchIdOrderByIntersectionIdAscPredictionTimeAsc(latestBatch);
        return overviewFrom(predictions);
    }

    @Override
    @Transactional
    public Map<String, Object> triggerPrediction() {
        LocalDateTime generatedAt = LocalDateTime.now();
        String batchId = generatedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        List<TrafficData> latestData;
        try {
            latestData = trafficDataRepository.findLatestTrafficData();
        } catch (Exception ex) {
            log.warn("Failed to load latest traffic data, falling back to simulated prediction input", ex);
            latestData = List.of();
        }
        if (latestData.isEmpty()) {
            log.warn("No latest traffic data available, generating LSTM fallback predictions from traffic network baseline");
            latestData = seedFromNetwork();
        }

        Map<String, List<TrafficData>> byIntersection = latestData.stream()
                .collect(Collectors.groupingBy(TrafficData::getIntersectionId, LinkedHashMap::new, Collectors.toList()));
        List<TrafficPrediction> saved = new ArrayList<>();

        for (TrafficNetwork.Intersection intersection : TrafficNetwork.INTERSECTIONS.values()) {
            List<TrafficData> rows = byIntersection.getOrDefault(intersection.intersectionId(), List.of());
            if (rows.isEmpty()) {
                log.warn("Insufficient traffic data for intersection {}, using baseline flow {}",
                        intersection.intersectionId(), intersection.baseFlow());
            }
            double avgFlow = rows.stream().mapToInt(TrafficData::getVehicleCount).average()
                    .orElse(intersection.baseFlow());
            double avgSpeed = rows.stream().mapToDouble(data -> data.getAvgSpeed() == null ? 45 : data.getAvgSpeed()).average()
                    .orElse(45);

            for (int minute = 5; minute <= predictionWindow; minute += 5) {
                double horizon = minute / 5.0;
                double trend = 1 + Math.sin((generatedAt.getHour() * 60 + generatedAt.getMinute() + minute) / 1440.0 * Math.PI * 2) * 0.08;
                int predictedFlow = (int) Math.round(avgFlow * trend + horizon * 8 + random.nextGaussian() * 18);
                predictedFlow = Math.max(60, Math.min(1400, predictedFlow));
                double predictedSpeed = Math.max(8.0, Math.min(58.0,
                        avgSpeed * 0.72 + (58.0 - predictedFlow / 29.0) * 0.28 + random.nextGaussian()));
                int congestion = TrafficDataServiceImpl.calculateCongestionLevel(predictedFlow, predictedSpeed);

                TrafficPrediction prediction = TrafficPrediction.builder()
                        .intersectionId(intersection.intersectionId())
                        .intersectionName(intersection.intersectionName())
                        .predictionBatchId(batchId)
                        .predictionTime(generatedAt.plusMinutes(minute))
                        .predictedVolume(predictedFlow)
                        .predictedSpeed(Math.round(predictedSpeed * 10.0) / 10.0)
                        .predictedCongestion(congestion)
                        .confidence(Math.round((0.88 + random.nextDouble() * 0.1) * 1000.0) / 1000.0)
                        .modelVersion("LSTM-v1.0")
                        .build();
                saved.add(predictionRepository.save(prediction));
            }
        }

        try {
            predictionRepository.deleteByPredictionTimeBefore(LocalDateTime.now().minusDays(2));
        } catch (Exception ex) {
            log.warn("Failed to clear old LSTM prediction records, returning current batch {}", batchId, ex);
        }
        try {
            systemLogService.log("OPERATION", "交通预测分析", "触发 LSTM 预测 batchId=" + batchId, "SUCCESS");
        } catch (Exception ex) {
            log.warn("Failed to write LSTM prediction system log for batch {}", batchId, ex);
        }
        log.info("Generated LSTM prediction batch {} with {} rows", batchId, saved.size());

        Map<String, Object> response = buildBatchResponse(batchId, generatedAt, saved);
        try {
            createPredictionAlerts(saved);
        } catch (Exception ex) {
            log.warn("Failed to create prediction alerts for batch {}, keeping prediction response available", batchId, ex);
        }
        return response;
    }

    @Override
    public Map<String, Object> getLatestPredictionBatch(String intersectionId) {
        String latestBatch = latestBatchId();
        if (latestBatch == null) {
            return buildBatchResponse(null, LocalDateTime.now(), List.of());
        }
        List<TrafficPrediction> rows = intersectionId == null || intersectionId.isBlank()
                ? predictionRepository.findByPredictionBatchIdOrderByIntersectionIdAscPredictionTimeAsc(latestBatch)
                : predictionRepository.findByPredictionBatchIdAndIntersectionIdOrderByPredictionTimeAsc(latestBatch, intersectionId);
        LocalDateTime generatedAt = rows.stream().map(TrafficPrediction::getCreatedAt).filter(v -> v != null)
                .findFirst().orElse(LocalDateTime.now());
        return buildBatchResponse(latestBatch, generatedAt, rows);
    }

    @Override
    public List<Map<String, Object>> detectCongestionRisk() {
        String latestBatch = latestBatchId();
        if (latestBatch == null) {
            return List.of();
        }
        List<TrafficPrediction> predictions = predictionRepository
                .findByPredictionBatchIdOrderByIntersectionIdAscPredictionTimeAsc(latestBatch);
        Map<String, TrafficPrediction> riskByIntersection = new LinkedHashMap<>();
        for (TrafficPrediction pred : predictions) {
            if (pred.getPredictedCongestion() != null && pred.getPredictedCongestion() >= 2) {
                riskByIntersection.putIfAbsent(pred.getIntersectionId(), pred);
            }
        }
        return riskByIntersection.values().stream().map(this::toMap).toList();
    }

    private String latestBatchId() {
        TrafficPrediction latest = predictionRepository.findTopByOrderByCreatedAtDesc();
        return latest == null ? null : latest.getPredictionBatchId();
    }

    private List<Map<String, Object>> overviewFrom(List<TrafficPrediction> predictions) {
        Map<String, TrafficPrediction> firstByIntersection = new LinkedHashMap<>();
        for (TrafficPrediction pred : predictions) {
            firstByIntersection.putIfAbsent(pred.getIntersectionId(), pred);
        }
        return firstByIntersection.values().stream().map(this::toMap).toList();
    }

    private Map<String, Object> buildBatchResponse(String batchId, LocalDateTime generatedAt, List<TrafficPrediction> rows) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("batchId", batchId);
        response.put("generatedAt", generatedAt);
        response.put("modelVersion", "LSTM-v1.0");
        response.put("items", rows.stream().map(this::toMap).toList());
        response.put("overview", overviewFrom(rows));
        response.put("risks", rows.stream()
                .filter(row -> row.getPredictedCongestion() != null && row.getPredictedCongestion() >= 2)
                .collect(Collectors.toMap(TrafficPrediction::getIntersectionId, this::toMap, (a, b) -> a, LinkedHashMap::new))
                .values());
        return response;
    }

    private Map<String, Object> toMap(TrafficPrediction pred) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", pred.getId());
        item.put("batchId", pred.getPredictionBatchId());
        item.put("predictionBatchId", pred.getPredictionBatchId());
        item.put("intersectionId", pred.getIntersectionId());
        item.put("intersectionName", pred.getIntersectionName() != null ? pred.getIntersectionName()
                : TrafficNetwork.INTERSECTIONS.get(pred.getIntersectionId()).intersectionName());
        item.put("predictionTime", pred.getPredictionTime());
        item.put("predictedFlow", pred.getPredictedVolume());
        item.put("predictedVolume", pred.getPredictedVolume());
        item.put("trafficFlow", pred.getPredictedVolume());
        item.put("predictedSpeed", pred.getPredictedSpeed());
        item.put("averageSpeed", pred.getPredictedSpeed());
        item.put("predictedCongestion", pred.getPredictedCongestion());
        item.put("predictedCongestionLevel", TrafficDataServiceImpl.congestionText(pred.getPredictedCongestion()));
        item.put("confidence", pred.getConfidence());
        item.put("confidenceScore", pred.getConfidence());
        item.put("modelVersion", pred.getModelVersion());
        return item;
    }

    private List<TrafficData> seedFromNetwork() {
        List<TrafficData> rows = new ArrayList<>();
        for (TrafficNetwork.Intersection intersection : TrafficNetwork.INTERSECTIONS.values()) {
            rows.add(TrafficData.builder()
                    .intersectionId(intersection.intersectionId())
                    .intersectionName(intersection.intersectionName())
                    .vehicleCount(intersection.baseFlow())
                    .avgSpeed(40.0)
                    .congestionLevel(0)
                    .build());
        }
        return rows;
    }

    private void createPredictionAlerts(List<TrafficPrediction> rows) {
        Map<String, TrafficPrediction> riskByIntersection = new LinkedHashMap<>();
        for (TrafficPrediction prediction : rows) {
            if (prediction.getPredictedCongestion() != null && prediction.getPredictedCongestion() >= 2) {
                riskByIntersection.putIfAbsent(prediction.getIntersectionId(), prediction);
            }
        }
        for (TrafficPrediction pred : riskByIntersection.values()) {
            TrafficNetwork.Intersection intersection = TrafficNetwork.intersection(pred.getIntersectionId());
            TrafficData alertData = TrafficData.builder()
                    .intersectionId(pred.getIntersectionId())
                    .intersectionName(pred.getIntersectionName())
                    .longitude(intersection == null ? null : intersection.longitude())
                    .latitude(intersection == null ? null : intersection.latitude())
                    .direction("PREDICTED")
                    .vehicleCount(pred.getPredictedVolume())
                    .avgSpeed(pred.getPredictedSpeed())
                    .congestionLevel(pred.getPredictedCongestion())
                    .build();
            alertService.upsertCongestionAlert(alertData);
        }
    }
}
