package com.itms.repository;

import com.itms.entity.TrafficAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TrafficAlertRepository extends JpaRepository<TrafficAlert, Long> {

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
