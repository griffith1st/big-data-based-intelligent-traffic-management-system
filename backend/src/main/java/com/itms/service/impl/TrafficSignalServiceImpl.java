package com.itms.service.impl;

import com.itms.common.TrafficNetwork;
import com.itms.entity.TrafficData;
import com.itms.entity.TrafficSignal;
import com.itms.repository.TrafficDataRepository;
import com.itms.repository.TrafficSignalRepository;
import com.itms.service.SystemLogService;
import com.itms.service.TrafficSignalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Locale;
import java.util.Set;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrafficSignalServiceImpl implements TrafficSignalService {

    private final TrafficSignalRepository signalRepository;
    private final TrafficDataRepository trafficDataRepository;
    private final SystemLogService systemLogService;

    @Override
    @Transactional
    public List<TrafficSignal> getSignals(String intersectionId) {
        TrafficValidation.requireIntersection(intersectionId);
        signalRepository.lockIntersection(intersectionId);
        List<TrafficSignal> signals = signalRepository.findByIntersectionId(intersectionId);
        return signals.size() != 4 ? createDefaultSignals(intersectionId) : sortSignals(signals);
    }

    @Override
    @Transactional
    public Map<String, Object> getCurrent(String intersectionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intersectionId", intersectionId);
        result.put("intersectionName", intersectionName(intersectionId));
        List<TrafficSignal> signals = getSignals(intersectionId);
        result.put("signals", signals);
        boolean valid = validPlan(signals);
        result.put("planValid", valid);
        result.put("validationMessage", valid ? "" : "Legacy timing is inconsistent; optimize and apply a new simulation plan");
        result.put("candidateSignals", signals.stream().filter(signal -> signal.getCandidateGreenDuration() != null)
                .map(this::candidateView).toList());
        result.put("algorithm", "WEIGHTED_RULES");
        result.put("executionMode", "SIMULATION");
        result.put("directionStates", directionStates(intersectionId));
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> optimizeSignals(String intersectionId, String strategy) {
        TrafficValidation.requireIntersection(intersectionId);
        signalRepository.lockIntersection(intersectionId);
        String normalized = normalizeStrategy(strategy);
        List<DirectionState> states = directionStates(intersectionId);
        Map<String, DirectionState> byDirection = states.stream()
                .collect(Collectors.toMap(DirectionState::direction, state -> state, (a, b) -> a, LinkedHashMap::new));

        double nsWeight = phaseWeight(byDirection.get("NORTH"), normalized)
                + phaseWeight(byDirection.get("SOUTH"), normalized);
        double ewWeight = phaseWeight(byDirection.get("EAST"), normalized)
                + phaseWeight(byDirection.get("WEST"), normalized);
        double totalWeight = Math.max(1.0, nsWeight + ewWeight);

        int yellow = 5;
        int cycleLength = clamp((int) Math.round(90 + totalWeight / 95.0), 80, 160);
        int allocatableGreen = cycleLength - yellow * 2;
        int minGreen = Math.max(30, allocatableGreen - 90);
        int maxGreen = Math.min(90, allocatableGreen - 30);
        int nsGreen = clamp((int) Math.round(allocatableGreen * nsWeight / totalWeight), minGreen, maxGreen);
        int ewGreen = allocatableGreen - nsGreen;

        if ("EMERGENCY_FIRST".equals(normalized)) {
            DirectionState emergency = states.stream().filter(DirectionState::emergencyVehicleDetected)
                    .max(Comparator.comparingDouble(DirectionState::weight)).orElse(null);
            if (emergency != null) {
                if (emergency.direction().equals("NORTH") || emergency.direction().equals("SOUTH")) {
                    nsGreen = clamp(nsGreen + 15, minGreen, maxGreen);
                    ewGreen = allocatableGreen - nsGreen;
                } else {
                    ewGreen = clamp(ewGreen + 15, minGreen, maxGreen);
                    nsGreen = allocatableGreen - ewGreen;
                }
            }
        }

        double beforeWait = estimateAverageWait(getSignals(intersectionId), states);
        List<TrafficSignal> optimized = upsertSignals(intersectionId, normalized, nsGreen, ewGreen, yellow, cycleLength);
        double afterWait = estimateAverageWait(optimized, states);
        double improvement = beforeWait <= 0 ? 0 : (beforeWait - afterWait) / beforeWait * 100.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intersectionId", intersectionId);
        result.put("intersectionName", intersectionName(intersectionId));
        result.put("strategy", normalized);
        result.put("algorithm", "WEIGHTED_RULES");
        result.put("executionMode", "SIMULATION");
        result.put("status", "OPTIMIZED");
        result.put("beforeAverageWait", round(beforeWait, 1));
        result.put("afterAverageWait", round(afterWait, 1));
        result.put("improvementRate", round(improvement, 1));
        result.put("cycleLength", cycleLength);
        result.put("signals", optimized);
        result.put("directionStates", states);
        result.put("phases", List.of(
                phase("NS_PHASE", List.of("NORTH", "SOUTH"), nsGreen, yellow, cycleLength),
                phase("EW_PHASE", List.of("EAST", "WEST"), ewGreen, yellow, cycleLength)
        ));
        String dominant = nsWeight >= ewWeight ? "NORTH/SOUTH" : "EAST/WEST";
        int dominantGreen = nsWeight >= ewWeight ? nsGreen : ewGreen;
        int otherGreen = nsWeight >= ewWeight ? ewGreen : nsGreen;
        result.put("explanation", String.format(
                "检测到 %s 方向综合权重较高，系统将该相位绿灯调整为 %d 秒，另一相位调整为 %d 秒，按规则模型估算的等待时间改善为 %.1f%%（负值表示增加，尚未应用）。",
                dominant, dominantGreen, otherGreen, improvement));

        systemLogService.log("OPERATION", "信号灯优化",
                "优化 " + intersectionId + " 策略=" + normalized, "SUCCESS");
        return result;
    }

    @Override
    @Transactional
    public void applySignalPlan(String intersectionId) {
        TrafficValidation.requireIntersection(intersectionId);
        signalRepository.lockIntersection(intersectionId);
        List<TrafficSignal> signals = getSignals(intersectionId);
        if (signals.size() != 4 || signals.stream().anyMatch(signal -> signal.getCandidateGreenDuration() == null))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Optimize a candidate plan before applying it");
        if (signals.stream().anyMatch(signal -> signal.getCandidateYellowDuration() == null || signal.getCandidateCycleDuration() == null)
                || !validPlan(signals.stream().map(this::candidateView).toList()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Candidate plan violates cycle constraints; optimize again");
        LocalDateTime appliedAt = LocalDateTime.now();
        for (TrafficSignal signal : signals) {
            signal.setGreenDuration(signal.getCandidateGreenDuration());
            signal.setYellowDuration(signal.getCandidateYellowDuration());
            signal.setCycleDuration(signal.getCandidateCycleDuration());
            signal.setRedDuration(signal.getCycleDuration() - signal.getGreenDuration() - signal.getYellowDuration());
            signal.setStrategy(signal.getCandidateStrategy());
            signal.setOptimizationStatus("APPLIED");
            signal.setAppliedAt(appliedAt);
            signal.setCandidateGreenDuration(null);
            signal.setCandidateYellowDuration(null);
            signal.setCandidateCycleDuration(null);
            signal.setCandidateStrategy(null);
            signalRepository.save(signal);
        }
        systemLogService.log("OPERATION", "信号灯优化", "应用模拟方案 " + intersectionId, "SUCCESS");
    }

    @Override
    @Transactional
    public List<Map<String, Object>> getSignalOverview() {
        return TrafficNetwork.INTERSECTIONS.values().stream().map(node -> {
            List<TrafficSignal> signals = getSignals(node.intersectionId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("intersectionId", node.intersectionId());
            item.put("intersectionName", node.intersectionName());
            item.put("status", signals.get(0).getOptimizationStatus());
            item.put("strategy", signals.get(0).getStrategy());
            item.put("signalCount", signals.size());
            item.put("avgCycle", signals.stream().mapToInt(TrafficSignal::getCycleDuration).average().orElse(0));
            return item;
        }).toList();
    }

    @Override
    public List<Map<String, Object>> getSignalIntersections() {
        return TrafficNetwork.INTERSECTIONS.values().stream().map(node -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("intersectionId", node.intersectionId());
            item.put("intersectionName", node.intersectionName());
            item.put("longitude", node.longitude());
            item.put("latitude", node.latitude());
            return item;
        }).toList();
    }

    private List<DirectionState> directionStates(String intersectionId) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        Map<String, TrafficData> latest = trafficDataRepository.findLatestTrafficData().stream()
                .filter(data -> data.getRecordTime() != null && !data.getRecordTime().isBefore(cutoff))
                .filter(data -> intersectionId.equals(data.getIntersectionId()))
                .collect(Collectors.toMap(TrafficData::getDirection, data -> data, (a, b) -> a, LinkedHashMap::new));
        List<DirectionState> states = new ArrayList<>();
        TrafficNetwork.Intersection intersection = TrafficNetwork.intersection(intersectionId);
        int base = intersection == null ? 600 : intersection.baseFlow();
        for (String direction : TrafficNetwork.DIRECTIONS) {
            TrafficData data = latest.get(direction);
            int vehicleCount = data == null ? Math.max(80, base / 2) : data.getVehicleCount();
            double speed = data == null || data.getAvgSpeed() == null ? 42.0 : data.getAvgSpeed();
            int congestion = data == null || data.getCongestionLevel() == null ? 0 : data.getCongestionLevel();
            double queueLength = Math.max(0, vehicleCount / 10.0 - speed / 5.0);
            double waitingTime = queueLength * 2.6 + congestion * 14.0;
            boolean emergency = false;
            double weight = vehicleCount * 0.5 + queueLength * 10.0 + waitingTime * 0.3;
            weight *= switch (congestion) {
                case 1 -> 1.2;
                case 2 -> 1.5;
                case 3 -> 2.0;
                default -> 1.0;
            };
            states.add(new DirectionState(direction, vehicleCount, round(speed, 1), round(queueLength, 1),
                    TrafficDataServiceImpl.congestionText(congestion), congestion, round(waitingTime, 1),
                    emergency, round(weight, 1), data != null, data == null ? "BASELINE_ESTIMATE" : "OBSERVED",
                    data == null ? null : data.getRecordTime()));
        }
        return states;
    }

    private double phaseWeight(DirectionState state, String strategy) {
        if (state == null) {
            return 1;
        }
        double weight = state.weight();
        if ("CONGESTION_FIRST".equals(strategy) || "EMERGENCY_FIRST".equals(strategy)) {
            weight *= 1.0 + state.congestionLevelValue() * 0.28;
        }
        return weight;
    }

    private List<TrafficSignal> upsertSignals(String intersectionId, String strategy,
                                              int nsGreen, int ewGreen, int yellow, int cycleLength) {
        Map<String, TrafficSignal> existing = getSignals(intersectionId).stream()
                .collect(Collectors.toMap(TrafficSignal::getDirection, signal -> signal));
        List<TrafficSignal> result = new ArrayList<>();
        for (String direction : TrafficNetwork.DIRECTIONS) {
            boolean ns = direction.equals("NORTH") || direction.equals("SOUTH");
            int green = ns ? nsGreen : ewGreen;
            TrafficSignal signal = existing.getOrDefault(direction, TrafficSignal.builder()
                    .intersectionId(intersectionId)
                    .direction(direction)
                    .build());
            signal.setCandidateGreenDuration(green);
            signal.setCandidateYellowDuration(yellow);
            signal.setCandidateCycleDuration(cycleLength);
            signal.setCandidateStrategy(strategy);
            signal.setOptimizationStatus("OPTIMIZED");
            signalRepository.save(signal);
            result.add(candidateView(signal));
        }
        return sortSignals(result);
    }

    private List<TrafficSignal> createDefaultSignals(String intersectionId) {
        for (String direction : TrafficNetwork.DIRECTIONS) {
            int phase = direction.equals("NORTH") || direction.equals("SOUTH") ? 1 : 2;
            signalRepository.initializeDirection(intersectionId, direction, phase);
        }
        return sortSignals(signalRepository.findByIntersectionId(intersectionId));
    }

    private TrafficSignal candidateView(TrafficSignal signal) {
        return TrafficSignal.builder().id(signal.getId()).intersectionId(signal.getIntersectionId())
                .direction(signal.getDirection()).phaseId(signal.getPhaseId())
                .greenDuration(signal.getCandidateGreenDuration()).yellowDuration(signal.getCandidateYellowDuration())
                .cycleDuration(signal.getCandidateCycleDuration())
                .redDuration(signal.getCandidateCycleDuration() - signal.getCandidateGreenDuration() - signal.getCandidateYellowDuration())
                .strategy(signal.getCandidateStrategy()).optimizationStatus("OPTIMIZED")
                .build();
    }

    private boolean validPlan(List<TrafficSignal> signals) {
        if (signals.size() != 4) return false;
        Map<String, TrafficSignal> directions = new LinkedHashMap<>();
        for (TrafficSignal signal : signals) {
            if (signal.getGreenDuration() == null || signal.getYellowDuration() == null
                    || signal.getRedDuration() == null || signal.getCycleDuration() == null
                    || signal.getGreenDuration() <= 0 || signal.getYellowDuration() <= 0 || signal.getRedDuration() < 0
                    || signal.getGreenDuration() + signal.getYellowDuration() + signal.getRedDuration() != signal.getCycleDuration()) return false;
            directions.put(signal.getDirection(), signal);
        }
        if (!directions.keySet().containsAll(TrafficNetwork.DIRECTIONS)) return false;
        TrafficSignal north = directions.get("NORTH"), east = directions.get("EAST");
        for (TrafficSignal signal : signals) {
            TrafficSignal phase = signal.getDirection().equals("NORTH") || signal.getDirection().equals("SOUTH") ? north : east;
            if (!signal.getGreenDuration().equals(phase.getGreenDuration()) || !signal.getYellowDuration().equals(phase.getYellowDuration())
                    || !signal.getCycleDuration().equals(north.getCycleDuration())) return false;
        }
        return north.getGreenDuration() + north.getYellowDuration() + east.getGreenDuration() + east.getYellowDuration() == north.getCycleDuration();
    }

    private List<TrafficSignal> sortSignals(List<TrafficSignal> signals) {
        Map<String, Integer> order = Map.of("NORTH", 0, "SOUTH", 1, "EAST", 2, "WEST", 3);
        return signals.stream()
                .sorted(Comparator.comparingInt(signal -> order.getOrDefault(signal.getDirection(), 9)))
                .toList();
    }

    private double estimateAverageWait(List<TrafficSignal> signals, List<DirectionState> states) {
        Map<String, TrafficSignal> byDirection = signals.stream()
                .collect(Collectors.toMap(TrafficSignal::getDirection, signal -> signal, (a, b) -> a));
        double weightedWait = 0;
        double totalVehicles = 0;
        for (DirectionState state : states) {
            TrafficSignal signal = byDirection.get(state.direction());
            double cycle = signal == null || signal.getCycleDuration() == null ? 90 : signal.getCycleDuration();
            double green = signal == null || signal.getGreenDuration() == null ? 40 : signal.getGreenDuration();
            double wait = state.waitingTime() + Math.max(0, cycle - green) * 0.35;
            weightedWait += wait * state.vehicleCount();
            totalVehicles += state.vehicleCount();
        }
        return totalVehicles <= 0 ? 0 : weightedWait / totalVehicles;
    }

    private Map<String, Object> phase(String phaseId, List<String> directions,
                                      int greenDuration, int yellowDuration, int cycleLength) {
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("phaseId", phaseId);
        phase.put("directions", directions);
        phase.put("greenDuration", greenDuration);
        phase.put("yellowDuration", yellowDuration);
        phase.put("redDuration", Math.max(15, cycleLength - greenDuration - yellowDuration));
        return phase;
    }

    private String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return "BALANCED";
        }
        String normalized = strategy.toUpperCase(Locale.ROOT);
        if (!Set.of("BALANCED", "CONGESTION_FIRST", "EMERGENCY_FIRST").contains(normalized))
            throw new IllegalArgumentException("Unsupported signal strategy: " + strategy);
        return normalized;
    }

    private String intersectionName(String intersectionId) {
        TrafficNetwork.Intersection intersection = TrafficNetwork.intersection(intersectionId);
        return intersection == null ? intersectionId : intersection.intersectionName();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    public record DirectionState(String direction, int vehicleCount, double averageSpeed,
                                 double queueLength, String congestionLevel, int congestionLevelValue,
                                 double waitingTime, boolean emergencyVehicleDetected, double weight,
                                 boolean observed, String source, LocalDateTime recordTime) {
    }
}
