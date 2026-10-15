package com.itms.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;

public interface TrafficIngestionService {
    void ingest(ConsumerRecord<String, String> record);
}
