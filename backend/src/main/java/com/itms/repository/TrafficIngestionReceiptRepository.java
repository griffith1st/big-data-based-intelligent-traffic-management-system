package com.itms.repository;

import com.itms.entity.TrafficIngestionReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TrafficIngestionReceiptRepository extends JpaRepository<TrafficIngestionReceipt, String> {

    @Modifying
    @Query(value = "INSERT INTO traffic_ingestion_receipts(event_key, received_at) "
            + "VALUES (:eventKey, CURRENT_TIMESTAMP) ON CONFLICT (event_key) DO NOTHING", nativeQuery = true)
    int claim(@Param("eventKey") String eventKey);
}
