package com.itms.service.impl;

import com.itms.entity.TrafficAlert;
import com.itms.entity.TrafficData;
import com.itms.repository.TrafficAlertRepository;
import com.itms.repository.TrafficAlertWriter;
import com.itms.service.SystemLogService;
import com.itms.service.TrafficAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TrafficAlertServiceImpl implements TrafficAlertService {

    private final TrafficAlertRepository alertRepository;
    private final SystemLogService systemLogService;
    private final TrafficAlertWriter alertWriter;

    @Override
    public List<TrafficAlert> getAllAlerts() {
        return alertRepository.findTop500ByOrderByCreatedAtDescIdDesc();
    }

    @Override
    public List<TrafficAlert> getUnreadAlerts() {
        return alertRepository.findTop500ByStatusOrderByCreatedAtDescIdDesc("UNREAD");
    }

    @Override
    public long getUnreadCount() {
        return alertRepository.countByStatus("UNREAD");
    }

    @Override
    @Transactional
    public void markAsRead(Long alertId) {
        requireAlert(alertId);
        if (alertRepository.markUnreadAsRead(alertId) > 0)
            systemLogService.log("OPERATION", "告警管理", "标记告警已读: " + alertId, "SUCCESS");
    }

    @Override
    @Transactional
    public void resolveAlert(Long alertId, String handler) {
        requireAlert(alertId);
        if (handler == null || handler.isBlank() || handler.length() > 50)
            throw new IllegalArgumentException("handler is required and must contain at most 50 characters");
        if (alertRepository.resolveActive(alertId, handler, LocalDateTime.now()) > 0)
            systemLogService.log("OPERATION", "告警管理", "处理告警: " + alertId, "SUCCESS");
    }

    private void requireAlert(Long id) {
        if (id == null || !alertRepository.existsById(id))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found");
    }

    @Override
    @Transactional
    public TrafficAlert createAlert(TrafficAlert alert) {
        if (alert == null || alert.getId() != null) throw new IllegalArgumentException("New alert is required");
        if (alert.getTitle() == null || alert.getTitle().isBlank() || alert.getTitle().length() > 200
                || alert.getContent() == null || alert.getContent().isBlank())
            throw new IllegalArgumentException("Alert title and content are required");
        if (!List.of("INFO", "WARNING", "CRITICAL").contains(alert.getAlertLevel()))
            throw new IllegalArgumentException("Unsupported alert level");
        if (alert.getIntersectionId() != null) TrafficValidation.requireIntersection(alert.getIntersectionId());
        alert.setStatus("UNREAD"); alert.setHandler(null); alert.setHandledAt(null); alert.setActiveKey(null);
        alert.setLastSeenAt(LocalDateTime.now());
        return alertRepository.save(alert);
    }

    @Override
    @Transactional
    public void upsertCongestionAlert(TrafficData data) {
        TrafficValidation.requireIntersection(data.getIntersectionId());
        boolean prediction = "PREDICTED".equals(data.getDirection());
        String source = prediction ? "PREDICTION" : "REALTIME";
        String direction = data.getDirection();
        if (!prediction && !com.itms.common.TrafficNetwork.DIRECTIONS.contains(direction))
            throw new IllegalArgumentException("Unsupported alert direction");
        String activeKey = source + ":" + data.getIntersectionId() + ":" + direction;
        LocalDateTime observedAt = data.getRecordTime() == null ? LocalDateTime.now() : data.getRecordTime();
        Integer congestionLevel = data.getCongestionLevel();
        if (congestionLevel == null || congestionLevel <= 0) {
            alertWriter.resolveRecovered(activeKey, source, data, observedAt);
            return;
        }
        String level = switch (congestionLevel) { case 1 -> "INFO"; case 2 -> "WARNING"; default -> "CRITICAL"; };
        String title = (prediction ? "模拟预测：" : "") + switch (congestionLevel) {
            case 1 -> "轻度拥堵提示"; case 2 -> "中度拥堵告警"; default -> "严重拥堵告警";
        };
        String content = String.format("%s %s 车流量 %d 辆，平均速度 %.1f km/h，拥堵等级 %s。",
                data.getIntersectionName(), direction, data.getVehicleCount(),
                data.getAvgSpeed(), TrafficDataServiceImpl.congestionText(congestionLevel));
        alertWriter.upsert(TrafficAlert.builder().activeKey(activeKey).source(source).direction(direction)
                .lastSeenAt(observedAt).title(title).content(content).alertLevel(level)
                .alertType("CONGESTION").intersectionId(data.getIntersectionId())
                .longitude(data.getLongitude()).latitude(data.getLatitude()).status("UNREAD").build(), data.getId());
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
