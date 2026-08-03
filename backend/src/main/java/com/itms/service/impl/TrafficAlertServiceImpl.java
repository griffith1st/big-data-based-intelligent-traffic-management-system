package com.itms.service.impl;

import com.itms.entity.TrafficAlert;
import com.itms.entity.TrafficData;
import com.itms.repository.TrafficAlertRepository;
import com.itms.service.SystemLogService;
import com.itms.service.TrafficAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TrafficAlertServiceImpl implements TrafficAlertService {

    private final TrafficAlertRepository alertRepository;
    private final SystemLogService systemLogService;

    @Override
    public List<TrafficAlert> getAllAlerts() {
        return alertRepository.findAllByOrderByCreatedAtDesc();
    }

    @Override
    public List<TrafficAlert> getUnreadAlerts() {
        return alertRepository.findByStatusOrderByCreatedAtDesc("UNREAD");
    }

    @Override
    public long getUnreadCount() {
        return alertRepository.countByStatus("UNREAD");
    }

    @Override
    public void markAsRead(Long alertId) {
        TrafficAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new RuntimeException("Alert not found: " + alertId));
        if ("UNREAD".equals(alert.getStatus())) {
            alert.setStatus("READ");
            alertRepository.save(alert);
            systemLogService.log("OPERATION", "告警管理", "标记告警已读: " + alertId, "SUCCESS");
        }
    }

    @Override
    public void resolveAlert(Long alertId, String handler) {
        TrafficAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new RuntimeException("Alert not found: " + alertId));
        alert.setStatus("RESOLVED");
        alert.setHandler(handler);
        alert.setHandledAt(LocalDateTime.now());
        alertRepository.save(alert);
        systemLogService.log("OPERATION", "告警管理", "处理告警: " + alertId, "SUCCESS");
    }

    @Override
    public TrafficAlert createAlert(TrafficAlert alert) {
        if (alert.getStatus() == null) {
            alert.setStatus("UNREAD");
        }
        return alertRepository.save(alert);
    }

    @Override
    public void upsertCongestionAlert(TrafficData data) {
        Integer congestionLevel = data.getCongestionLevel();
        if (congestionLevel == null || congestionLevel <= 0) {
            return;
        }

        String level = switch (congestionLevel) {
            case 1 -> "INFO";
            case 2 -> "WARNING";
            default -> "CRITICAL";
        };
        String title = switch (congestionLevel) {
            case 1 -> "轻度拥堵提示";
            case 2 -> "中度拥堵告警";
            default -> "严重拥堵告警";
        };

        LocalDateTime since = LocalDateTime.now().minusMinutes(10);
        List<TrafficAlert> recent = alertRepository
                .findByIntersectionIdAndAlertTypeAndCreatedAtAfterOrderByCreatedAtDesc(
                        data.getIntersectionId(), "CONGESTION", since);
        TrafficAlert alert = recent.stream()
                .filter(item -> !"RESOLVED".equals(item.getStatus()))
                .findFirst()
                .orElse(null);

        String content = String.format("%s %s 车流量 %d 辆，平均速度 %.1f km/h，拥堵等级 %s。",
                data.getIntersectionName(), data.getDirection(), data.getVehicleCount(),
                data.getAvgSpeed(), TrafficDataServiceImpl.congestionText(congestionLevel));

        if (alert == null) {
            alert = TrafficAlert.builder()
                    .title(title)
                    .content(content)
                    .alertLevel(level)
                    .alertType("CONGESTION")
                    .intersectionId(data.getIntersectionId())
                    .longitude(data.getLongitude())
                    .latitude(data.getLatitude())
                    .status("UNREAD")
                    .build();
        } else {
            alert.setTitle(title);
            alert.setContent(content);
            alert.setAlertLevel(level);
            alert.setLongitude(data.getLongitude());
            alert.setLatitude(data.getLatitude());
        }
        alertRepository.save(alert);
    }

    @Override
    public Map<String, Object> getAlertStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long total = alertRepository.count();
        long unread = alertRepository.countByStatus("UNREAD");
        stats.put("totalCount", total);
        stats.put("unreadCount", unread);

        Map<String, Long> byLevel = new LinkedHashMap<>();
        for (String level : List.of("INFO", "WARNING", "CRITICAL")) {
            long count = alertRepository.countByAlertLevel(level);
            byLevel.put(level, count);
            stats.put(level, count);
        }
        stats.put("byLevel", byLevel);

        Map<String, Long> activeByLevel = new LinkedHashMap<>();
        for (String level : List.of("INFO", "WARNING", "CRITICAL")) {
            activeByLevel.put(level, alertRepository.countByAlertLevelAndStatusNot(level, "RESOLVED"));
        }
        stats.put("activeByLevel", activeByLevel);
        return stats;
    }
}
