package com.itms.config;

import com.itms.service.TrafficIngestionService;
import com.itms.service.impl.TrafficKafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class KafkaConsumerConfigTest {
    // Scanning the owned configuration package lets this test fail on missing policy beans.
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = KafkaTopicConfig.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "com\\.itms\\.config\\.Kafka(Topic|Consumer)Config"))
    static class KafkaConfigScan { }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    static class ListenerTestConfig {
        @Bean ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")));
            factory.setAutoStartup(false);
            return factory;
        }
    }

    private static final ConsumerRecord<String, String> RECORD =
            new ConsumerRecord<>("traffic-test", 2, 17L, "INT-001:NORTH", "{broken-json");

    private ApplicationContextRunner context(MockProducer<String, String> producer) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(() -> producer);
        return new ApplicationContextRunner().withUserConfiguration(KafkaConfigScan.class)
                .withBean(KafkaTemplate.class, () -> template)
                .withPropertyValues("itms.kafka.consumer.retry-interval-ms=0",
                        "itms.kafka.consumer.max-retries=2");
    }

    @Test void configuredConcurrencyIsAppliedToRegisteredTrafficListener() {
        context(new RecordingProducer(false)).withUserConfiguration(ListenerTestConfig.class)
                .withBean(TrafficKafkaConsumer.class, () -> new TrafficKafkaConsumer(
                        mock(TrafficIngestionService.class)))
                .withPropertyValues("itms.kafka.consumer.concurrency=3")
                .run(ctx -> {
                    var registry = ctx.getBean(KafkaListenerEndpointRegistry.class);
                    assertThat(registry.getListenerContainers()).singleElement().satisfies(container ->
                            assertThat(((ConcurrentMessageListenerContainer<?, ?>) container).getConcurrency())
                                    .isEqualTo(3));
                });
    }

    @Test void businessFailureRetriesTwiceThenPublishesOriginalRecordToMatchingDltPartition() {
        var producer = new RecordingProducer(false);
        context(producer).run(ctx -> {
            assertThat(ctx).hasSingleBean(DefaultErrorHandler.class);
            var handler = ctx.getBean(DefaultErrorHandler.class);
            var failure = new IllegalStateException("database unavailable");
            assertThat(handler.handleOne(failure, RECORD, null, null)).isFalse();
            assertThat(handler.handleOne(failure, RECORD, null, null)).isFalse();
            assertThat(producer.history()).isEmpty();
            assertThat(handler.handleOne(failure, RECORD, null, null)).isTrue();
            assertThat(producer.history()).singleElement().satisfies(sent -> {
                assertThat(sent.topic()).isEqualTo("traffic-test.DLT");
                assertThat(sent.partition()).isEqualTo(2);
                assertThat(sent.key()).isEqualTo("INT-001:NORTH");
                assertThat(sent.value()).isEqualTo("{broken-json");
            });
        });
    }

    @Test void failedAsynchronousDltSendIsRaisedByRecoverer() {
        context(new RecordingProducer(true)).run(ctx -> {
            assertThat(ctx).hasSingleBean(DeadLetterPublishingRecoverer.class);
            var recoverer = ctx.getBean(DeadLetterPublishingRecoverer.class);
            assertThatThrownBy(() -> recoverer.accept(RECORD, null, new IllegalStateException("db")))
                    .isInstanceOf(KafkaException.class);
        });
    }

    @Test void exhaustedRecordIsNotReportedRecoveredWhenDltBrokerFails() {
        context(new RecordingProducer(true)).run(ctx -> {
            assertThat(ctx).hasSingleBean(DefaultErrorHandler.class);
            var handler = ctx.getBean(DefaultErrorHandler.class);
            var failure = new IllegalStateException("database unavailable");
            assertThat(handler.handleOne(failure, RECORD, null, null)).isFalse();
            assertThat(handler.handleOne(failure, RECORD, null, null)).isFalse();
            assertThat(handler.handleOne(failure, RECORD, null, null)).isFalse();
        });
    }

    /** Broker boundary only: the template, recoverer and retry policy remain real. */
    private static class RecordingProducer extends MockProducer<String, String> {
        private final boolean fail;

        RecordingProducer(boolean fail) {
            super(false, new StringSerializer(), new StringSerializer());
            this.fail = fail;
        }

        @Override public synchronized Future<RecordMetadata> send(
                ProducerRecord<String, String> record, Callback callback) {
            var result = super.send(record, callback);
            if (fail) errorNext(new IllegalStateException("DLT broker unavailable"));
            else completeNext();
            return result;
        }

        @Override public void close(Duration timeout) { }
    }
}
