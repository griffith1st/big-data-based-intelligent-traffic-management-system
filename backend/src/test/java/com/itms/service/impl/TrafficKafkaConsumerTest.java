package com.itms.service.impl;

import com.itms.service.TrafficIngestionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrafficKafkaConsumerTest {
    private final TrafficIngestionService ingestion = mock(TrafficIngestionService.class);
    private final TrafficKafkaConsumer consumer = new TrafficKafkaConsumer(ingestion);

    @Test void listenerPassesSourceTopicPartitionOffsetToIngestionService() {
        var record = new ConsumerRecord<>("traffic-data", 2, 17L, "INT-001:NORTH", "{}");
        consumer.consume(record);
        verify(ingestion).ingest(record);
    }

    // Swallowing a DB failure would let the container acknowledge an unsaved record.
    @Test void ingestionFailureEscapesToContainerForRetry() {
        var outage = new IllegalStateException("database unavailable");
        var record = new ConsumerRecord<>("traffic-data", 0, 4L, "key", "{}");
        doThrow(outage).when(ingestion).ingest(record);
        assertThatThrownBy(() -> consumer.consume(record)).isSameAs(outage);
    }
}
