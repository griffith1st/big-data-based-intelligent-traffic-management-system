package com.itms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.entity.TrafficData;
import com.itms.service.TrafficDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrafficKafkaConsumer {

    private final ObjectMapper objectMapper;
    private final TrafficDataService trafficDataService;

    @KafkaListener(topics = "${itms.kafka.topic.traffic-data:traffic-data}",
            groupId = "${spring.kafka.consumer.group-id:itms-traffic-consumer}")
    public void consume(String payload) {
        try {
            TrafficData data = objectMapper.readValue(payload, TrafficData.class);
            trafficDataService.saveTrafficData(data);
        } catch (Exception ex) {
            log.warn("Failed to consume traffic-data Kafka message: {}", payload, ex);
        }
    }
}
