package com.itms.repository;

import com.itms.entity.TrafficData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 交通数据数据访问层
 */
@Repository
public interface TrafficDataRepository extends JpaRepository<TrafficData, Long> {

    /** 按路口和时间范围查询 */
    List<TrafficData> findByIntersectionIdAndRecordTimeBetweenOrderByRecordTimeAsc(
            String intersectionId, LocalDateTime start, LocalDateTime end);

    /** 按时间范围查询 */
    List<TrafficData> findByRecordTimeBetweenOrderByRecordTimeAsc(
            LocalDateTime start, LocalDateTime end);

    /** 获取最新的交通数据 */
    @Query("SELECT t FROM TrafficData t WHERE t.recordTime = " +
           "(SELECT MAX(t2.recordTime) FROM TrafficData t2 " +
           "WHERE t2.intersectionId = t.intersectionId AND t2.direction = t.direction)")
    List<TrafficData> findLatestTrafficData();

    /** 获取最新一批所有数据的拥堵等级（按路口汇总，原生SQL保证正确性） */
    @Query(value = "SELECT t.intersection_id, " +
           "CAST(MAX(t.congestion_level) AS INTEGER) AS max_level, " +
           "CAST(AVG(t.congestion_level) AS DOUBLE PRECISION) AS avg_level " +
           "FROM traffic_data t " +
           "WHERE t.record_time = (SELECT MAX(record_time) FROM traffic_data) " +
           "GROUP BY t.intersection_id", nativeQuery = true)
    List<Object[]> findCurrentCongestionNative();

    /** 按路口统计最近N分钟的数据 */
    @Query("SELECT t FROM TrafficData t WHERE t.intersectionId = :intersectionId " +
           "AND t.recordTime >= :since ORDER BY t.recordTime DESC")
    List<TrafficData> findRecentByIntersection(
            @Param("intersectionId") String intersectionId,
            @Param("since") LocalDateTime since);

    /** 获取所有路口的实时拥堵等级 */
    @Query("SELECT t.id, t.intersectionId, t.intersectionName, t.congestionLevel, " +
           "t.longitude, t.latitude, t.vehicleCount, t.avgSpeed, t.recordTime, t.direction, t.dataSource, t.occupancyRate " +
           "FROM TrafficData t WHERE t.recordTime = " +
           "(SELECT MAX(t2.recordTime) FROM TrafficData t2 " +
           "WHERE t2.intersectionId = t.intersectionId AND t2.direction = t.direction) " +
           "ORDER BY t.intersectionId ASC, t.direction ASC")
    List<Object[]> findRealTimeCongestion();

    /** 删除超过 N 小时的历史数据 */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM traffic_data WHERE record_time < NOW() - CAST(:hours AS INTEGER) * INTERVAL '1 hour'", nativeQuery = true)
    int deleteOldData(@Param("hours") int hours);
}

