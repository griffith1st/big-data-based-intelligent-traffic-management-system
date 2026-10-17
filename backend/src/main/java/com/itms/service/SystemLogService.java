package com.itms.service;

import com.itms.entity.SystemLog;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;

public interface SystemLogService {

    void log(SystemLog log);

    void log(String logType, String module, String description, String status);

    List<SystemLog> getAllLogs();

    List<SystemLog> getLogsByType(String logType);

    List<SystemLog> getLogsByUser(Long userId);

    Map<String, Object> getSummary();

    Page<SystemLog> search(String logType, Long userId, int page, int size);
}
