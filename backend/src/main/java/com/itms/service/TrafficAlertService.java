package com.itms.service;

import com.itms.entity.TrafficAlert;
import com.itms.entity.TrafficData;

import java.util.List;
import java.util.Map;

public interface TrafficAlertService {

    List<TrafficAlert> getAllAlerts();

    List<TrafficAlert> getUnreadAlerts();

    long getUnreadCount();

    void markAsRead(Long alertId);

    void resolveAlert(Long alertId, String handler);

    TrafficAlert createAlert(TrafficAlert alert);

    void upsertCongestionAlert(TrafficData data);

    Map<String, Object> getAlertStatistics();
}
