package com.itms.service;

import com.itms.entity.TrafficData;

import java.util.List;
import java.util.Map;

public interface TrafficDataService {

    List<Map<String, Object>> getRealTimeData();

    Map<String, Object> getTrafficSummary();

    List<TrafficData> getHistoryData(String intersectionId, int minutes);

    List<TrafficData> getHistoryData(String intersectionId, Integer limit);

    List<Map<String, Object>> getHeatmapData();

    List<Map<String, Object>> getIntersectionSummary();

    TrafficData saveTrafficData(TrafficData data);

    List<TrafficData> batchSave(List<TrafficData> dataList);
}
