package com.itms.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DeadLetterPublishingRecoverer trafficDeadLetterRecoverer(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        // An asynchronous DLT send failure must not look like successful recovery.
        recoverer.setFailIfSendResultIsError(true);
        // Keep the original partition; a missing DLT partition must fail the send.
        recoverer.setVerifyPartition(false);
        return recoverer;
    }

    @Bean
    public DefaultErrorHandler trafficKafkaErrorHandler(
            DeadLetterPublishingRecoverer recoverer,
            @Value("${itms.kafka.consumer.retry-interval-ms:1000}") long retryIntervalMs,
            @Value("${itms.kafka.consumer.max-retries:2}") int maxRetries) {
        // Boot installs this CommonErrorHandler on its listener container factory.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(retryIntervalMs, maxRetries));
    }
}
