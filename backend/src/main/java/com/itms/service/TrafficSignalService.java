package com.itms.service;

import com.itms.entity.TrafficSignal;

import java.util.List;
import java.util.Map;

public interface TrafficSignalService {

    List<TrafficSignal> getSignals(String intersectionId);

    Map<String, Object> getCurrent(String intersectionId);

    Map<String, Object> optimizeSignals(String intersectionId, String strategy);

    void applySignalPlan(String intersectionId);

    List<Map<String, Object>> getSignalOverview();

    List<Map<String, Object>> getSignalIntersections();
}
