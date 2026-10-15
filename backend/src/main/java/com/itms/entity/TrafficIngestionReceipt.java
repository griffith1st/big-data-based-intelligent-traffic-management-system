package com.itms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Durable claim for one Kafka delivery position. The key includes the consumer
 * group, so replaying a record in the same group is ignored while a different
 * group can process its own copy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "traffic_ingestion_receipts")
public class TrafficIngestionReceipt {

    @Id
    @Column(name = "event_key", nullable = false, length = 64)
    private String eventKey;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;
}
