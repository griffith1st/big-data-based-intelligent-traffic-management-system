package com.itms.controller;

import com.itms.common.Result;
import com.itms.entity.RoutePlan;
import com.itms.service.RoutePlanService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itms.security.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "路线规划")
public class RoutePlanController {

    private final RoutePlanService routePlanService;

    @PostMapping("/api/route/plan")
    public Result<RoutePlan> planRoute(
            @RequestParam Double startLng,
            @RequestParam Double startLat,
            @RequestParam Double endLng,
            @RequestParam Double endLat,
            @RequestParam(defaultValue = "SHORTEST") String strategy,
            @RequestParam(required = false) Long userId) {
        return Result.success(routePlanService.planRoute(startLng, startLat, endLng, endLat, strategy, authenticatedUserId(userId)));
    }

    @PostMapping("/api/route/green")
    public Result<RoutePlan> planGreenRoute(
            @RequestParam Double startLng,
            @RequestParam Double startLat,
            @RequestParam Double endLng,
            @RequestParam Double endLat,
            @RequestParam(required = false) Long userId) {
        return Result.success(routePlanService.planGreenRoute(startLng, startLat, endLng, endLat, authenticatedUserId(userId)));
    }

    @GetMapping("/api/route/history/{userId}")
    public Result<List<RoutePlan>> getUserRoutes(@PathVariable Long userId) {
        return Result.success(routePlanService.getUserRoutes(authenticatedUserId(userId)));
    }

    @GetMapping("/api/routes/intersections")
    public Result<List<Map<String, Object>>> intersections() {
        return Result.success(routePlanService.getIntersections());
    }

    @GetMapping("/api/routes/network")
    public Result<Map<String, Object>> network() {
        return Result.success(routePlanService.getNetwork());
    }

    @PostMapping("/api/routes/plan")
    public Result<RoutePlan> planRouteByIntersection(@RequestBody Map<String, Object> request) {
        String start = String.valueOf(request.get("startIntersectionId"));
        String end = String.valueOf(request.get("endIntersectionId"));
        String strategy = String.valueOf(request.getOrDefault("strategy", "SHORTEST"));
        Long userId = request.get("userId") instanceof Number number ? number.longValue() : null;
        return Result.success(routePlanService.planRouteByIntersection(start, end, strategy, authenticatedUserId(userId)));
    }

    @GetMapping("/api/routes/history")
    public Result<List<RoutePlan>> routeHistory(@RequestParam(required = false) Long userId) {
        if (userId != null) {
            return Result.success(routePlanService.getUserRoutes(authenticatedUserId(userId)));
        }
        return Result.success(routePlanService.getUserRoutes(authenticatedUserId(null)));
    }
    private Long authenticatedUserId(Long requestedUserId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");
        if (requestedUserId != null && !requestedUserId.equals(principal.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only your own route history is accessible");
        return principal.userId();
    }

}
