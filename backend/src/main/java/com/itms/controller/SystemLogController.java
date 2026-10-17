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
import org.springframework.data.domain.Page;

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
        return Result.success(systemLogService.search(logType, userId, 0, 100).getContent());
    }

    @GetMapping("/page")
    public Result<Page<SystemLog>> page(@RequestParam(required=false) String logType,
            @RequestParam(required=false) Long userId,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return Result.success(systemLogService.search(logType, userId, page, size));
    }

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.success(systemLogService.getSummary());
    }
}
