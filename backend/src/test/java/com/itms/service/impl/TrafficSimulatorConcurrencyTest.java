package com.itms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.service.TrafficDataService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrafficSimulatorConcurrencyTest {
    @Test @SuppressWarnings("unchecked") void overlappingManualGenerationIsRejectedWithoutPublishingAnotherBatch() throws Exception {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var service = new TrafficSimulatorServiceImpl(kafka, new ObjectMapper().findAndRegisterModules(), mock(TrafficDataService.class));
        ReflectionTestUtils.setField(service, "topic", "traffic-data");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sends = new AtomicInteger();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            if (sends.incrementAndGet() == 1) {
                entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return CompletableFuture.<SendResult<String, String>>completedFuture(null);
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(service::generateOnce);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(service::generateOnce).isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(503));
            release.countDown(); assertThat(first.get(5, TimeUnit.SECONDS)).hasSize(56);
            verify(kafka, times(56)).send(anyString(), anyString(), anyString());
        } finally {
            release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
