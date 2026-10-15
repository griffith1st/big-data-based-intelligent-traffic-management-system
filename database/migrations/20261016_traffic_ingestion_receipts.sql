-- Apply before starting the consumer with ingestion receipts enabled.
-- Run against the same PostgreSQL database/schema as traffic_data.
-- This migration is idempotent and does not alter existing traffic records.
BEGIN;

CREATE TABLE IF NOT EXISTS traffic_ingestion_receipts (
    event_key VARCHAR(64) PRIMARY KEY,
    received_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE traffic_ingestion_receipts IS
    'Committed Kafka delivery receipts: SHA-256 of consumer group, topic, partition and offset';
COMMENT ON COLUMN traffic_ingestion_receipts.event_key IS
    'Same delivery position is processed once per group while its receipt is retained';

COMMIT;

-- Retain receipts for at least the full Kafka replay/retention window.
-- Replaying a DLT payload at a new source offset is a new delivery, and requires
-- separate business event identification if it should be deduplicated.
-- Deleting this table/data also removes the protection against old offsets.
