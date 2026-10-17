package com.itms.repository;

import com.itms.entity.RoutePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 路径规划数据访问层
 */
@Repository
public interface RoutePlanRepository extends JpaRepository<RoutePlan, Long> {

    /** 按用户查询历史路径 */
    List<RoutePlan> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<RoutePlan> findTop500ByUserIdOrderByCreatedAtDescIdDesc(Long userId);
    List<RoutePlan> findTop500ByOrderByCreatedAtDescIdDesc();

    /** 按策略查询 */
    List<RoutePlan> findByStrategy(String strategy);
}
