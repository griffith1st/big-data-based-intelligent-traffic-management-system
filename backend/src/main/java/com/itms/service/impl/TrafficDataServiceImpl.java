package com.itms.service.impl;

import com.itms.common.TrafficNetwork;
import com.itms.entity.TrafficData;
import com.itms.repository.TrafficDataRepository;
import com.itms.service.TrafficAlertService;
import com.itms.service.TrafficDataService;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class TrafficDataServiceImpl implements TrafficDataService {

    private final TrafficDataRepository trafficDataRepository;
    private final TrafficAlertService alertService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final String REALTIME_CACHE_KEY = "itms:traffic:realtime";
    private static final String SUMMARY_CACHE_KEY = "itms:traffic:intersection-summary";
    private static final java.time.Duration TRAFFIC_CACHE_TTL = java.time.Duration.ofSeconds(5);
    private static final TypeReference<List<Map<String, Object>>> TRAFFIC_ROWS = new TypeReference<>() {};

    // These guards coordinate this application instance only. Redis keys are
    // shared, but cache generations and in-flight loads are process-local.
    private final Object cacheMutationLock = new Object();
    private volatile long cacheGeneration;
    private final Map<CacheLoadKey, CompletableFuture<List<Map<String, Object>>>> inFlightLoads = new ConcurrentHashMap<>();

    @Value("${itms.traffic.cache.wait-timeout-ms:1500}")
    private long cacheWaitMillis = 1500;

    private record CacheLoadKey(String key, long generation) {}

    @Override
    public List<Map<String, Object>> getRealTimeData() {
        return cachedRows(REALTIME_CACHE_KEY, this::loadRealTimeData);
    }

    private List<Map<String, Object>> loadRealTimeData() {
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
        return cachedRows(SUMMARY_CACHE_KEY, this::loadIntersectionSummary);
    }

    private List<Map<String, Object>> loadIntersectionSummary() {
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
        try {
            alertService.upsertCongestionAlert(saved);
        } finally {
            // The repository write succeeded. A subsequent alert failure
            // must not skip invalidation of the traffic update.
            evictAfterCommitIfNeeded();
        }
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

    private List<Map<String, Object>> cachedRows(String key, Supplier<List<Map<String, Object>>> loader) {
        // Preserve the caller's transaction view, including its own writes.
        // Neither consume a shared snapshot nor publish uncommitted data.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return loader.get();
        }
        // Capture before reading any dependency. A summary started after a
        // write must not join an older real-time load still in progress.
        long generation = cacheGeneration;
        List<Map<String, Object>> cached = readCache(key, TRAFFIC_ROWS);
        if (cached != null) {
            return cached;
        }
        CacheLoadKey loadKey = new CacheLoadKey(key, generation);
        CompletableFuture<List<Map<String, Object>>> mine = new CompletableFuture<>();
        CompletableFuture<List<Map<String, Object>>> existing = inFlightLoads.putIfAbsent(loadKey, mine);
        if (existing != null) {
            return awaitLoad(existing);
        }
        try {
            // A previous owner may have populated Redis between GET and
            // putIfAbsent. Redis outage still shares this owner's DB result.
            cached = readCache(key, TRAFFIC_ROWS);
            List<Map<String, Object>> result = cached != null ? cached : loader.get();
            synchronized (cacheMutationLock) {
                if (cached == null && generation == cacheGeneration) {
                    writeCache(key, result);
                }
            }
            mine.complete(result);
            return result;
        } catch (RuntimeException | Error ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            // Only the owner removes a flight. A timed-out follower must not
            // allow another DB load while the first owner is still running.
            inFlightLoads.remove(loadKey, mine);
        }
    }

    private List<Map<String, Object>> awaitLoad(CompletableFuture<List<Map<String, Object>>> load) {
        try {
            return load.get(Math.max(1, cacheWaitMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Traffic cache reload is busy; retry later", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Traffic request interrupted", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (ex.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Traffic cache reload failed", ex.getCause());
        }
    }

    private <T> T readCache(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, type);
        } catch (Exception e) {
            // Cache is an acceleration layer; malformed/stale data falls back to PostgreSQL.
            return null;
        }
    }

    private void writeCache(String key, Object value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), TRAFFIC_CACHE_TTL);
        } catch (Exception ignored) {
            // Database result has already been produced; a Redis outage must not fail the API.
        }
    }

    private void evictTrafficCaches() {
        synchronized (cacheMutationLock) {
            try {
                redis.delete(List.of(REALTIME_CACHE_KEY, SUMMARY_CACHE_KEY));
            } catch (Exception ignored) {
                // Existing Redis entries retain their original short TTL.
                // The local generation still prevents older loads refilling.
            } finally {
                // Advance only after DEL has completed. A reader that began
                // while invalidation was in progress remains in the old
                // generation and cannot publish after this point.
                cacheGeneration++;
            }
        }
    }

    private boolean transactionalContext() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }

    private void evictAfterCommitIfNeeded() {
        if (!transactionalContext()) {
            evictTrafficCaches();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictTrafficCaches();
            }
        });
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
