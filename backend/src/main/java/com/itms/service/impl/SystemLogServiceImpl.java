package com.itms.service.impl;

import com.itms.entity.SystemLog;
import com.itms.repository.SystemLogRepository;
import com.itms.service.SystemLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SystemLogServiceImpl implements SystemLogService {

    private final SystemLogRepository systemLogRepository;

    @Override
    public void log(SystemLog log) {
        systemLogRepository.save(log);
    }

    @Override
    public void log(String logType, String module, String description, String status) {
        systemLogRepository.save(SystemLog.builder()
                .username("system")
                .logType(logType)
                .module(module)
                .description(description)
                .status(status)
                .build());
    }

    @Override
    public List<SystemLog> getAllLogs() {
        return systemLogRepository.findAllByOrderByCreatedAtDesc();
    }

    @Override
    public List<SystemLog> getLogsByType(String logType) {
        return systemLogRepository.findByLogTypeOrderByCreatedAtDesc(logType);
    }

    @Override
    public List<SystemLog> getLogsByUser(Long userId) {
        return systemLogRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    public Map<String, Object> getSummary() {
        List<SystemLog> logs = getAllLogs();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCount", logs.size());
        summary.put("operationCount", logs.stream().filter(log -> "OPERATION".equals(log.getLogType())).count());
        summary.put("loginCount", logs.stream().filter(log -> "LOGIN".equals(log.getLogType())).count());
        summary.put("failedCount", logs.stream().filter(log -> "FAILED".equals(log.getStatus())).count());

        Map<String, Long> byType = new LinkedHashMap<>();
        for (String type : List.of("LOGIN", "OPERATION", "SYSTEM", "ERROR")) {
            byType.put(type, logs.stream().filter(log -> type.equals(log.getLogType())).count());
        }
        summary.put("byType", byType);

        Map<String, Long> byDay = new LinkedHashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = 6; i >= 0; i--) {
            LocalDate day = LocalDate.now().minusDays(i);
            byDay.put(day.format(formatter), 0L);
        }
        for (SystemLog log : logs) {
            if (log.getCreatedAt() != null && !log.getCreatedAt().toLocalDate().isBefore(LocalDate.now().minusDays(6))) {
                String key = log.getCreatedAt().format(formatter);
                byDay.computeIfPresent(key, (ignored, count) -> count + 1);
            }
        }
        summary.put("byDay", byDay);
        return summary;
    }
}
