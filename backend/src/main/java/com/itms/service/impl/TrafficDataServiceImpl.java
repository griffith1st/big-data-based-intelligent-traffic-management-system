package com.itms.service.impl;

import com.itms.common.TrafficNetwork;
import com.itms.entity.TrafficData;
import com.itms.repository.TrafficDataRepository;
import com.itms.service.TrafficAlertService;
import com.itms.service.TrafficDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TrafficDataServiceImpl implements TrafficDataService {

    private final TrafficDataRepository trafficDataRepository;
    private final TrafficAlertService alertService;

    @Override
    public List<Map<String, Object>> getRealTimeData() {
        ensureSeedData();
        List<Object[]> rawData = trafficDataRepository.findRealTimeCongestion();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rawData) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row[0]);
            item.put("intersectionId", row[1]);
            item.put("intersectionName", row[2]);
            item.put("congestionLevel", row[3]);
            item.put("congestionLevelText", congestionText(asInt(row[3])));
            item.put("longitude", row[4]);
            item.put("latitude", row[5]);
            item.put("vehicleCount", row[6]);
            item.put("avgVehicleCount", row[6]);
            item.put("averageSpeed", row[7]);
            item.put("avgSpeed", row[7]);
            item.put("recordTime", row[8]);
            item.put("timestamp", row[8]);
            item.put("direction", row[9]);
            item.put("dataSource", row[10]);
            item.put("occupancyRate", row[11]);
            result.add(item);
        }
        return result;
    }

    @Override
    public Map<String, Object> getTrafficSummary() {
        List<Map<String, Object>> rows = getIntersectionSummary();
        int total = rows.stream().mapToInt(row -> asInt(row.get("totalVehicleCount"))).sum();
        double weightedSpeedSum = rows.stream()
                .mapToDouble(row -> asDouble(row.get("averageSpeed")) * asInt(row.get("totalVehicleCount")))
                .sum();
        double avgSpeed = total > 0 ? weightedSpeedSum / total : 0;
        long congested = rows.stream().filter(row -> asInt(row.get("worstCongestionLevelValue")) >= 2).count();
        long smooth = rows.stream().filter(row -> asInt(row.get("worstCongestionLevelValue")) == 0).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalVehicleCount", total);
        summary.put("averageSpeed", Math.round(avgSpeed * 10.0) / 10.0);
        summary.put("congestedCount", congested);
        summary.put("smoothCount", smooth);
        summary.put("dataPointCount", getRealTimeData().size());
        summary.put("intersectionCount", rows.size());
        return summary;
    }

    @Override
    public List<TrafficData> getHistoryData(String intersectionId, int minutes) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        return trafficDataRepository.findRecentByIntersection(intersectionId, since);
    }

    @Override
    public List<TrafficData> getHistoryData(String intersectionId, Integer limit) {
        int safeLimit = limit == null || limit <= 0 ? 100 : Math.min(limit, 1000);
        List<TrafficData> rows = trafficDataRepository
                .findRecentByIntersection(intersectionId, LocalDateTime.now().minusDays(2));
        return rows.stream()
                .sorted(Comparator.comparing(TrafficData::getRecordTime))
                .skip(Math.max(0, rows.size() - safeLimit))
                .toList();
    }

    @Override
    public List<Map<String, Object>> getHeatmapData() {
        return getIntersectionSummary();
    }

    @Override
    public List<Map<String, Object>> getIntersectionSummary() {
        List<Map<String, Object>> rows = getRealTimeData();
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            grouped.computeIfAbsent(String.valueOf(row.get("intersectionId")), ignored -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (TrafficNetwork.Intersection intersection : TrafficNetwork.INTERSECTIONS.values()) {
            List<Map<String, Object>> items = grouped.getOrDefault(intersection.intersectionId(), List.of());
            if (items.isEmpty()) {
                continue;
            }
            int total = items.stream().mapToInt(item -> asInt(item.get("vehicleCount"))).sum();
            double weightedSpeed = items.stream()
                    .mapToDouble(item -> asDouble(item.get("avgSpeed")) * asInt(item.get("vehicleCount")))
                    .sum();
            double avgSpeed = total > 0 ? weightedSpeed / total : 0;
            int worst = items.stream().mapToInt(item -> asInt(item.get("congestionLevel"))).max().orElse(0);
            Object timestamp = items.stream().map(item -> item.get("timestamp")).filter(v -> v != null)
                    .findFirst().orElse(null);

            List<Map<String, Object>> directionDetails = items.stream().map(item -> {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("direction", item.get("direction"));
                detail.put("vehicleCount", item.get("vehicleCount"));
                detail.put("averageSpeed", item.get("avgSpeed"));
                detail.put("congestionLevel", congestionText(asInt(item.get("congestionLevel"))));
                detail.put("congestionLevelValue", asInt(item.get("congestionLevel")));
                return detail;
            }).toList();

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("intersectionId", intersection.intersectionId());
            summary.put("intersectionName", intersection.intersectionName());
            summary.put("longitude", intersection.longitude());
            summary.put("latitude", intersection.latitude());
            summary.put("totalVehicleCount", total);
            summary.put("vehicleCount", total);
            summary.put("averageSpeed", Math.round(avgSpeed * 10.0) / 10.0);
            summary.put("avgSpeed", Math.round(avgSpeed * 10.0) / 10.0);
            summary.put("worstCongestionLevel", congestionText(worst));
            summary.put("worstCongestionLevelValue", worst);
            summary.put("congestionLevel", worst);
            summary.put("directionCount", items.size());
            summary.put("directionDetails", directionDetails);
            summary.put("updateTime", timestamp);
            summary.put("timestamp", timestamp);
            result.add(summary);
        }
        return result;
    }

    @Override
    public TrafficData saveTrafficData(TrafficData data) {
        normalize(data);
        TrafficData saved = trafficDataRepository.save(data);
        alertService.upsertCongestionAlert(saved);
        return saved;
    }

    @Override
    public List<TrafficData> batchSave(List<TrafficData> dataList) {
        List<TrafficData> savedList = new ArrayList<>();
        for (TrafficData data : dataList) {
            savedList.add(saveTrafficData(data));
        }
        return savedList;
    }

    private void ensureSeedData() {
        if (trafficDataRepository.count() > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (TrafficNetwork.Intersection intersection : TrafficNetwork.INTERSECTIONS.values()) {
            for (String direction : TrafficNetwork.DIRECTIONS) {
                TrafficData data = TrafficData.builder()
                        .intersectionId(intersection.intersectionId())
                        .intersectionName(intersection.intersectionName())
                        .longitude(intersection.longitude())
                        .latitude(intersection.latitude())
                        .direction(direction)
                        .vehicleCount(Math.max(80, intersection.baseFlow() / 2))
                        .avgSpeed(42.0)
                        .occupancyRate(35.0)
                        .congestionLevel(0)
                        .dataSource("SIMULATOR")
                        .recordTime(now)
                        .build();
                trafficDataRepository.save(data);
            }
        }
    }

    private void normalize(TrafficData data) {
        TrafficNetwork.Intersection intersection = TrafficNetwork.intersection(data.getIntersectionId());
        if (intersection != null) {
            data.setIntersectionName(intersection.intersectionName());
            data.setLongitude(intersection.longitude());
            data.setLatitude(intersection.latitude());
        }
        if (data.getRecordTime() == null) {
            data.setRecordTime(LocalDateTime.now());
        }
        if (data.getDataSource() == null || data.getDataSource().isBlank()) {
            data.setDataSource("KAFKA");
        }
        if (data.getOccupancyRate() == null) {
            data.setOccupancyRate(Math.min(100.0, Math.max(5.0, data.getVehicleCount() / 14.0)));
        }
        if (data.getCongestionLevel() == null) {
            data.setCongestionLevel(calculateCongestionLevel(data.getVehicleCount(), data.getAvgSpeed()));
        }
    }

    public static int calculateCongestionLevel(Integer vehicleCount, Double avgSpeed) {
        int count = vehicleCount == null ? 0 : vehicleCount;
        double speed = avgSpeed == null ? 60 : avgSpeed;
        if (speed >= 40 || count < 520) {
            return 0;
        }
        if (speed >= 30 && count < 760) {
            return 1;
        }
        if (speed >= 20 && count < 1050) {
            return 2;
        }
        return 3;
    }

    public static String congestionText(Integer level) {
        return switch (level == null ? 0 : level) {
            case 1 -> "LIGHT";
            case 2 -> "MEDIUM";
            case 3 -> "HEAVY";
            default -> "SMOOTH";
        };
    }

    private int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
