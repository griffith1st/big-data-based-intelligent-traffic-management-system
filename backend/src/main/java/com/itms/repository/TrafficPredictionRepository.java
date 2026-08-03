package com.itms.repository;

import com.itms.entity.TrafficPrediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TrafficPredictionRepository extends JpaRepository<TrafficPrediction, Long> {

    List<TrafficPrediction> findByIntersectionIdOrderByPredictionTimeAsc(String intersectionId);

    List<TrafficPrediction> findByIntersectionIdAndPredictionTimeBetweenOrderByPredictionTimeAsc(
            String intersectionId, LocalDateTime start, LocalDateTime end);

    List<TrafficPrediction> findByPredictionTimeBetweenOrderByPredictionTimeAsc(
            LocalDateTime start, LocalDateTime end);

    List<TrafficPrediction> findByPredictionBatchIdOrderByIntersectionIdAscPredictionTimeAsc(String predictionBatchId);

    List<TrafficPrediction> findByPredictionBatchIdAndIntersectionIdOrderByPredictionTimeAsc(
            String predictionBatchId, String intersectionId);

    TrafficPrediction findTopByOrderByCreatedAtDesc();

    void deleteByPredictionTimeBefore(LocalDateTime time);
}
