package com.itms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.entity.TrafficData;
import com.itms.repository.TrafficIngestionReceiptRepository;
import com.itms.service.TrafficDataService;
import com.itms.service.TrafficIngestionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrafficIngestionServiceImplTest {
    private TrafficIngestionReceiptRepository receipts;
    private TrafficDataService traffic;
    private TrafficIngestionServiceImpl service;

    @BeforeEach void setUp() {
        receipts = mock(TrafficIngestionReceiptRepository.class);
        traffic = mock(TrafficDataService.class);
        service = new TrafficIngestionServiceImpl(new ObjectMapper(), receipts, traffic);
        ReflectionTestUtils.setField(service, "consumerGroup", "itms-traffic-consumer");
    }

    @Test void sameTopicPartitionOffsetClaimsOnceAndSavesOnce() {
        Set<String> claimed = new HashSet<>();
        when(receipts.claim(anyString())).thenAnswer(call -> claimed.add(call.getArgument(0)) ? 1 : 0);
        var record = record("traffic-data", 3, 99L);
        service.ingest(record);
        service.ingest(record);
        verify(traffic, times(1)).saveTrafficData(any(TrafficData.class));
        assertThat(claimed).hasSize(1);
    }

    @Test void differentSourcePositionOrGroupIsASeparateDelivery() {
        Set<String> claimed = new HashSet<>();
        when(receipts.claim(anyString())).thenAnswer(call -> claimed.add(call.getArgument(0)) ? 1 : 0);
        service.ingest(record("traffic-data", 3, 99L));
        service.ingest(record("traffic-data", 3, 100L));
        service.ingest(record("traffic-data", 4, 99L));
        service.ingest(record("traffic-data.DLT", 3, 99L));
        ReflectionTestUtils.setField(service, "consumerGroup", "another-group");
        service.ingest(record("traffic-data", 3, 99L));
        verify(traffic, times(5)).saveTrafficData(any(TrafficData.class));
        assertThat(claimed).hasSize(5);
    }

    @Test void trafficFailureRollsBackTheIngestionTransaction() {
        var manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        var factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var proxied = (TrafficIngestionService) factory.getProxy();
        var failure = new IllegalStateException("alert database unavailable");
        when(receipts.claim(anyString())).thenReturn(1);
        doThrow(failure).when(traffic).saveTrafficData(any());
        assertThatThrownBy(() -> proxied.ingest(record("traffic-data", 0, 1L))).isSameAs(failure);
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test void failedReceiptClaimDoesNotAttemptTrafficWrite() {
        var failure = new IllegalStateException("receipt database unavailable");
        when(receipts.claim(anyString())).thenThrow(failure);
        assertThatThrownBy(() -> service.ingest(record("traffic-data", 0, 1L))).isSameAs(failure);
        verifyNoInteractions(traffic);
    }

    @Test void malformedPayloadDoesNotClaimOrSave() {
        assertThatThrownBy(() -> service.ingest(new ConsumerRecord<>("traffic-data", 0, 1L, "k", "{bad")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(receipts, traffic);
    }

    private ConsumerRecord<String, String> record(String topic, int partition, long offset) {
        return new ConsumerRecord<>(topic, partition, offset, "INT-001:NORTH",
                "{\"intersectionId\":\"INT-001\",\"vehicleCount\":120}");
    }
}
