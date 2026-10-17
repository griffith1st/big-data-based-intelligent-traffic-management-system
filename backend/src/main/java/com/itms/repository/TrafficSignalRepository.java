package com.itms.repository;

import com.itms.entity.TrafficSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 信号灯数据访问层
 */
@Repository
public interface TrafficSignalRepository extends JpaRepository<TrafficSignal, Long> {

    /** Transaction-scoped PostgreSQL lock also covers initialization before rows exist. */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:intersectionId))) locked", nativeQuery = true)
    int lockIntersection(@Param("intersectionId") String intersectionId);

    @Modifying
    @Query(value = "INSERT INTO traffic_signals(intersection_id,direction,green_duration,red_duration," +
            "yellow_duration,cycle_duration,phase_id,optimization_status,strategy,created_at,updated_at) " +
            "VALUES (:intersectionId,:direction,40,45,5,90,:phase,'PENDING','FIXED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) " +
            "ON CONFLICT DO NOTHING", nativeQuery = true)
    int initializeDirection(@Param("intersectionId") String intersectionId, @Param("direction") String direction,
                            @Param("phase") int phase);

    /** 按路口查询所有信号灯 */
    List<TrafficSignal> findByIntersectionId(String intersectionId);

    /** 按路口和方向查询 */
    Optional<TrafficSignal> findByIntersectionIdAndDirection(String intersectionId, String direction);

    /** 查询待优化的信号灯 */
    List<TrafficSignal> findByOptimizationStatus(String status);

    /** 按策略查询 */
    List<TrafficSignal> findByStrategy(String strategy);
}
