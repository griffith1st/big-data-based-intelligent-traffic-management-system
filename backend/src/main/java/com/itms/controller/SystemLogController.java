package com.itms.controller;

import com.itms.common.Result;
import com.itms.entity.SystemLog;
import com.itms.service.SystemLogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@Tag(name = "系统日志")
public class SystemLogController {

    private final SystemLogService systemLogService;

    @GetMapping
    public Result<List<SystemLog>> getLogs(
            @RequestParam(required = false) String logType,
            @RequestParam(required = false) Long userId) {
        if (userId != null) {
            return Result.success(systemLogService.getLogsByUser(userId));
        }
        if (logType != null && !logType.isBlank()) {
            return Result.success(systemLogService.getLogsByType(logType));
        }
        return Result.success(systemLogService.getAllLogs());
    }

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.success(systemLogService.getSummary());
    }
}
