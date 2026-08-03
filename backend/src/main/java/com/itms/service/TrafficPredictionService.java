package com.itms.service;

import com.itms.entity.TrafficPrediction;

import java.util.List;
import java.util.Map;

public interface TrafficPredictionService {

    List<TrafficPrediction> getPredictions(String intersectionId);

    List<Map<String, Object>> getPredictionOverview();

    Map<String, Object> triggerPrediction();

    Map<String, Object> getLatestPredictionBatch(String intersectionId);

    List<Map<String, Object>> detectCongestionRisk();
}
