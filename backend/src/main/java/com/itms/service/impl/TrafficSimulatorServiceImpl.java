package com.itms.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.common.TrafficNetwork;
import com.itms.entity.TrafficData;
import com.itms.service.TrafficDataService;
import com.itms.service.TrafficSimulatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficSimulatorServiceImpl implements TrafficSimulatorService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TrafficDataService trafficDataService;
    private final Random random = new Random();
    private final Map<String, Integer> previousCounts = new ConcurrentHashMap<>();
    private final ReentrantLock generationLock = new ReentrantLock();

    @Value("${itms.kafka.topic.traffic-data:traffic-data}")
    private String topic;

    @Value("${itms.traffic.simulator.enabled:true}")
    private volatile boolean running;

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Scheduled(fixedDelayString = "${itms.traffic.simulator.interval-ms:5000}", initialDelay = 2500)
    public void scheduledGenerate() {
        // A manual batch may already be running. Skip this tick instead of
        // queueing a second batch and racing the per-direction state.
        if (!running || !generationLock.tryLock()) {
            return;
        }
        try {
            if (running) {
                generateBatch();
            }
        } finally {
            generationLock.unlock();
        }
    }

    @Override
    public List<TrafficData> generateOnce() {
        if (!generationLock.tryLock()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "A traffic generation batch is already running");
        }
        try {
            return generateBatch();
        } finally {
            generationLock.unlock();
        }
    }

    private List<TrafficData> generateBatch() {
        LocalDateTime now = LocalDateTime.now();
        List<TrafficData> batch = new ArrayList<>();

        for (TrafficNetwork.Intersection intersection : TrafficNetwork.INTERSECTIONS.values()) {
            for (String direction : TrafficNetwork.DIRECTIONS) {
                batch.add(generate(intersection, direction, now));
            }
        }

        for (TrafficData data : batch) {
            publish(data);
        }
        return batch;
    }

    private TrafficData generate(TrafficNetwork.Intersection intersection, String direction, LocalDateTime now) {
        double factor = timeFactor(now) * dayFactor(now) * directionFactor(direction);
        String key = intersection.intersectionId() + ":" + direction;
        int target = (int) Math.round(intersection.baseFlow() * factor);
        int previous = previousCounts.getOrDefault(key, target);
        int maxStep = Math.max(25, (int) (intersection.baseFlow() * 0.08));
        int noise = random.nextInt(maxStep * 2 + 1) - maxStep;
        int next = (int) Math.round(previous * 0.72 + target * 0.28 + noise);
        next = Math.max(60, Math.min(1300, next));
        previousCounts.put(key, next);

        double speed = Math.max(8.0, Math.min(58.0, 58.0 - next / 28.0 + random.nextGaussian() * 1.8));
        int congestion = TrafficDataServiceImpl.calculateCongestionLevel(next, speed);

        return TrafficData.builder()
                .intersectionId(intersection.intersectionId())
                .intersectionName(intersection.intersectionName())
                .longitude(intersection.longitude())
                .latitude(intersection.latitude())
                .direction(direction)
                .vehicleCount(next)
                .avgSpeed(Math.round(speed * 10.0) / 10.0)
                .occupancyRate(Math.round(Math.min(98.0, Math.max(8.0, next / 13.0)) * 10.0) / 10.0)
                .congestionLevel(congestion)
                .dataSource("KAFKA")
                .recordTime(now)
                .build();
    }

    private double timeFactor(LocalDateTime now) {
        int hour = now.getHour();
        if (hour >= 7 && hour < 9) {
            return 1.45;
        }
        if (hour >= 17 && hour < 19) {
            return 1.55;
        }
        if (hour >= 0 && hour < 5) {
            return 0.42;
        }
        if (hour >= 11 && hour < 14) {
            return 1.08;
        }
        return 0.92;
    }

    private double dayFactor(LocalDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? 0.72 : 1.0;
    }

    private double directionFactor(String direction) {
        return switch (direction) {
            case "NORTH" -> 1.04;
            case "SOUTH" -> 0.98;
            case "EAST" -> 1.10;
            case "WEST" -> 0.94;
            default -> 1.0;
        };
    }

    private void publish(TrafficData data) {
        try {
            kafkaTemplate.send(topic, data.getIntersectionId() + ":" + data.getDirection(), objectMapper.writeValueAsString(data))
                    .exceptionally(ex -> {
                        log.warn("Kafka publish failed, saving traffic data directly: {}", ex.getMessage());
                        trafficDataService.saveTrafficData(data);
                        return null;
                    });
        } catch (JsonProcessingException ex) {
            Map<String, Object> minimal = new LinkedHashMap<>();
            minimal.put("intersectionId", data.getIntersectionId());
            minimal.put("direction", data.getDirection());
            log.warn("Traffic data serialization failed: {}", minimal, ex);
            trafficDataService.saveTrafficData(data);
        }
    }
}
