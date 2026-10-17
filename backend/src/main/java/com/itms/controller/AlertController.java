package com.itms.controller;

import com.itms.common.Result;
import com.itms.entity.TrafficAlert;
import com.itms.service.TrafficAlertService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "告警管理")
public class AlertController {

    private final TrafficAlertService alertService;

    @GetMapping({"/api/alert/list", "/api/alerts"})
    public Result<List<TrafficAlert>> getAllAlerts() {
        return Result.success(alertService.getAllAlerts());
    }

    @GetMapping({"/api/alert/unread", "/api/alerts/unread"})
    public Result<List<TrafficAlert>> getUnreadAlerts() {
        return Result.success(alertService.getUnreadAlerts());
    }

    @GetMapping({"/api/alert/unread-count", "/api/alerts/unread-count"})
    public Result<Long> getUnreadCount() {
        return Result.success(alertService.getUnreadCount());
    }

    @PutMapping("/api/alert/read/{id}")
    public Result<String> markAsRead(@PathVariable Long id) {
        alertService.markAsRead(id);
        return Result.success("已标记为已读");
    }

    @PostMapping("/api/alerts/{id}/read")
    public Result<String> markAsReadNew(@PathVariable Long id) {
        alertService.markAsRead(id);
        return Result.success("已标记为已读");
    }

    @PutMapping("/api/alert/resolve/{id}")
    public Result<String> resolveAlert(@PathVariable Long id,
                                       @RequestParam(defaultValue = "system") String handler) {
        alertService.resolveAlert(id, SecurityContextHolder.getContext().getAuthentication().getName());
        return Result.success("告警已处理");
    }

    @PostMapping("/api/alerts/{id}/process")
    public Result<String> resolveAlertNew(@PathVariable Long id,
                                          @RequestParam(defaultValue = "system") String handler) {
        alertService.resolveAlert(id, SecurityContextHolder.getContext().getAuthentication().getName());
        return Result.success("告警已处理");
    }

    @GetMapping({"/api/alert/statistics", "/api/alerts/summary"})
    public Result<Map<String, Object>> getStatistics() {
        return Result.success(alertService.getAlertStatistics());
    }
}
