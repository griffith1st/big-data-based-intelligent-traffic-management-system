package com.itms.service.impl;

import com.itms.service.TrafficIngestionService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TrafficKafkaConsumer {

    private final TrafficIngestionService ingestionService;

    @KafkaListener(topics = "${itms.kafka.topic.traffic-data:traffic-data}",
            groupId = "${spring.kafka.consumer.group-id:itms-traffic-consumer}",
            concurrency = "${itms.kafka.consumer.concurrency:1}")
    public void consume(ConsumerRecord<String, String> record) {
        // Let the container retry or publish to the DLT before advancing the offset.
        ingestionService.ingest(record);
    }
}
