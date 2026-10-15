package com.itms.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.entity.TrafficData;
import com.itms.repository.TrafficIngestionReceiptRepository;
import com.itms.service.TrafficDataService;
import com.itms.service.TrafficIngestionService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class TrafficIngestionServiceImpl implements TrafficIngestionService {

    private final ObjectMapper objectMapper;
    private final TrafficIngestionReceiptRepository receiptRepository;
    private final TrafficDataService trafficDataService;

    @Value("${spring.kafka.consumer.group-id:itms-traffic-consumer}")
    private String consumerGroup;

    /**
     * Receipt claim and traffic/alert persistence share one database transaction.
     * If saving traffic or its alert fails, the receipt insert rolls back and the
     * Kafka container can redeliver the original record.
     */
    @Override
    @Transactional
    public void ingest(ConsumerRecord<String, String> record) {
        TrafficData data = read(record.value());
        if (receiptRepository.claim(eventKey(record)) == 0) {
            return;
        }
        trafficDataService.saveTrafficData(data);
    }

    private TrafficData read(String payload) {
        try {
            return objectMapper.readValue(payload, TrafficData.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid traffic Kafka payload", ex);
        }
    }

    private String eventKey(ConsumerRecord<String, String> record) {
        String source = consumerGroup + "\u0000" + record.topic() + "\u0000"
                + record.partition() + "\u0000" + record.offset();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
