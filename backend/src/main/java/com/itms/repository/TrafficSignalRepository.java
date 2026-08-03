package com.itms.repository;

import com.itms.entity.TrafficSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 信号灯数据访问层
 */
@Repository
public interface TrafficSignalRepository extends JpaRepository<TrafficSignal, Long> {

    /** 按路口查询所有信号灯 */
    List<TrafficSignal> findByIntersectionId(String intersectionId);

    /** 按路口和方向查询 */
    Optional<TrafficSignal> findByIntersectionIdAndDirection(String intersectionId, String direction);

    /** 查询待优化的信号灯 */
    List<TrafficSignal> findByOptimizationStatus(String status);

    /** 按策略查询 */
    List<TrafficSignal> findByStrategy(String strategy);
}
