package com.itms.service.impl;

import com.itms.entity.SystemLog;
import com.itms.repository.SystemLogRepository;
import com.itms.service.SystemLogService;
import com.itms.security.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SystemLogServiceImpl implements SystemLogService {

    private final SystemLogRepository systemLogRepository;

    @Override
    public void log(SystemLog log) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (log.getUsername() == null && authentication != null && authentication.getPrincipal() instanceof UserPrincipal actor) {
            log.setUsername(actor.username());
            log.setUserId(actor.userId());
        }
        if (log.getUsername() == null) log.setUsername("system");
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            log.setIpAddress(request.getRemoteAddr());
            log.setMethod(request.getMethod());
            log.setRequestUri(request.getRequestURI());
        }
        systemLogRepository.save(log);
    }

    @Override
    public void log(String logType, String module, String description, String status) {
        log(SystemLog.builder()
                .logType(logType)
                .module(module)
                .description(description)
                .status(status)
                .build());
    }

    @Override
    public List<SystemLog> getAllLogs() {
        return search(null, null, 0, 100).getContent();
    }

    @Override
    public List<SystemLog> getLogsByType(String logType) {
        return search(logType, null, 0, 100).getContent();
    }

    @Override
    public List<SystemLog> getLogsByUser(Long userId) {
        return search(null, userId, 0, 100).getContent();
    }

    @Override
    public Page<SystemLog> search(String logType, Long userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (userId != null && userId < 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数或用户编号不合法");
        }
        String type = logType == null || logType.isBlank() ? null : logType.trim();
        if (type != null && !List.of("LOGIN", "OPERATION", "SYSTEM", "ERROR").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日志类型不合法");
        }
        Specification<SystemLog> filter = (root, query, cb) -> cb.conjunction();
        if (type != null) filter = filter.and((root, query, cb) -> cb.equal(root.get("logType"), type));
        if (userId != null) filter = filter.and((root, query, cb) -> cb.equal(root.get("userId"), userId));
        return systemLogRepository.findAll(filter,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    @Override
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Long> byType = new LinkedHashMap<>();
        for (String type : List.of("LOGIN", "OPERATION", "SYSTEM", "ERROR")) {
            byType.put(type, systemLogRepository.countByLogType(type));
        }
        summary.put("totalCount", systemLogRepository.count());
        summary.put("operationCount", byType.get("OPERATION"));
        summary.put("loginCount", byType.get("LOGIN"));
        summary.put("failedCount", systemLogRepository.countByStatus("FAILED"));
        summary.put("byType", byType);

        Map<String, Long> byDay = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = LocalDate.now().minusDays(i);
            byDay.put(day.toString(), 0L);
        }
        for (Object[] row : systemLogRepository.countByDaySince(LocalDate.now().minusDays(6).atStartOfDay())) {
            byDay.computeIfPresent(String.valueOf(row[0]), (ignored, count) -> ((Number) row[1]).longValue());
        }
        summary.put("byDay", byDay);
        return summary;
    }
}
