package com.itms.service;

import com.itms.entity.SystemLog;

import java.util.List;
import java.util.Map;

public interface SystemLogService {

    void log(SystemLog log);

    void log(String logType, String module, String description, String status);

    List<SystemLog> getAllLogs();

    List<SystemLog> getLogsByType(String logType);

    List<SystemLog> getLogsByUser(Long userId);

    Map<String, Object> getSummary();
}
