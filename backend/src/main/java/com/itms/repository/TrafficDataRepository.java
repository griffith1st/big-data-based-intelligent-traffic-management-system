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
import java.util.Optional;

/**
 * 交通数据数据访问层
 */
@Repository
public interface TrafficDataRepository extends JpaRepository<TrafficData, Long> {

    Optional<TrafficData> findByEventId(String eventId);

    /** Cross-instance serialization before looking up a potentially absent event. */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:eventId, 41977))) locked", nativeQuery = true)
    int lockEventId(@Param("eventId") String eventId);

    /** 按路口和时间范围查询 */
    List<TrafficData> findByIntersectionIdAndRecordTimeBetweenOrderByRecordTimeAsc(
            String intersectionId, LocalDateTime start, LocalDateTime end);

    /** 按时间范围查询 */
    List<TrafficData> findByRecordTimeBetweenOrderByRecordTimeAsc(
            LocalDateTime start, LocalDateTime end);

    /** 获取最新的交通数据 */
    @Query("SELECT t FROM TrafficData t WHERE NOT EXISTS (SELECT 1 FROM TrafficData t2 " +
           "WHERE t2.intersectionId = t.intersectionId AND t2.direction = t.direction " +
           "AND (t2.recordTime > t.recordTime OR (t2.recordTime = t.recordTime AND t2.id > t.id))) " +
           "ORDER BY t.intersectionId, t.direction")
    List<TrafficData> findLatestTrafficData();

    /** 获取最新一批所有数据的拥堵等级（按路口汇总，原生SQL保证正确性） */
    @Query(value = "SELECT t.intersection_id, " +
           "CAST(MAX(t.congestion_level) AS INTEGER) AS max_level, " +
           "CAST(AVG(t.congestion_level) AS DOUBLE PRECISION) AS avg_level " +
           "FROM traffic_data t " +
           "WHERE NOT EXISTS (SELECT 1 FROM traffic_data newer WHERE newer.intersection_id = t.intersection_id " +
           "AND newer.direction = t.direction AND (newer.record_time > t.record_time " +
           "OR (newer.record_time = t.record_time AND newer.id > t.id))) " +
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
           "FROM TrafficData t WHERE NOT EXISTS (SELECT 1 FROM TrafficData t2 " +
           "WHERE t2.intersectionId = t.intersectionId AND t2.direction = t.direction " +
           "AND (t2.recordTime > t.recordTime OR (t2.recordTime = t.recordTime AND t2.id > t.id))) " +
           "ORDER BY t.intersectionId ASC, t.direction ASC")
    List<Object[]> findRealTimeCongestion();

    /** Limit applies in PostgreSQL, so application memory is bounded. */
    @Query(value = "SELECT * FROM traffic_data WHERE intersection_id = :intersectionId " +
            "AND record_time >= :since ORDER BY record_time DESC, id DESC LIMIT :limit", nativeQuery = true)
    List<TrafficData> findRecentBounded(@Param("intersectionId") String intersectionId,
                                      @Param("since") LocalDateTime since, @Param("limit") int limit);

    /** 删除超过 N 小时的历史数据 */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM traffic_data WHERE record_time < NOW() - CAST(:hours AS INTEGER) * INTERVAL '1 hour'", nativeQuery = true)
    int deleteOldData(@Param("hours") int hours);
}

