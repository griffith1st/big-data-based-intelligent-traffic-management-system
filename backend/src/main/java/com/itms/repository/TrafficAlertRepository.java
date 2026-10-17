package com.itms.repository;

import com.itms.entity.TrafficAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TrafficAlertRepository extends JpaRepository<TrafficAlert, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TrafficAlert a SET a.status = 'READ' WHERE a.id = :id AND a.status = 'UNREAD'")
    int markUnreadAsRead(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TrafficAlert a SET a.status = 'RESOLVED', a.handler = :handler, " +
            "a.handledAt = :now, a.activeKey = null WHERE a.id = :id AND a.status <> 'RESOLVED'")
    int resolveActive(@Param("id") Long id, @Param("handler") String handler, @Param("now") LocalDateTime now);

    List<TrafficAlert> findTop500ByOrderByCreatedAtDescIdDesc();
    List<TrafficAlert> findTop500ByStatusOrderByCreatedAtDescIdDesc(String status);

    List<TrafficAlert> findAllByOrderByCreatedAtDesc();

    List<TrafficAlert> findByStatusOrderByCreatedAtDesc(String status);

    List<TrafficAlert> findByAlertLevelOrderByCreatedAtDesc(String alertLevel);

    List<TrafficAlert> findByAlertTypeOrderByCreatedAtDesc(String alertType);

    List<TrafficAlert> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);

    List<TrafficAlert> findByIntersectionIdOrderByCreatedAtDesc(String intersectionId);

    long countByStatus(String status);

    long countByAlertLevel(String alertLevel);

    long countByAlertLevelAndStatusNot(String alertLevel, String status);

    List<TrafficAlert> findByIntersectionIdAndAlertTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            String intersectionId, String alertType, LocalDateTime since);
}
