package com.itms.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;

class KafkaTopicConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(KafkaTopicConfig.class);

    @Test void configuredPartitionsAndReplicasApplyToSourceAndDeadLetterTopics() {
        context.withPropertyValues("itms.kafka.topic.traffic-data=traffic-test",
                "itms.kafka.topic.partitions=3", "itms.kafka.topic.replicas=2")
                .run(ctx -> {
                    NewTopic source = ctx.getBean("trafficDataTopic", NewTopic.class);
                    assertThat(source.name()).isEqualTo("traffic-test");
                    assertThat(source.numPartitions()).isEqualTo(3);
                    assertThat(source.replicationFactor()).isEqualTo((short) 2);
                    assertThat(ctx).hasBean("trafficDataDeadLetterTopic");
                    NewTopic deadLetter = ctx.getBean("trafficDataDeadLetterTopic", NewTopic.class);
                    assertThat(deadLetter.name()).isEqualTo("traffic-test.DLT");
                    assertThat(deadLetter.numPartitions()).isEqualTo(3);
                    assertThat(deadLetter.replicationFactor()).isEqualTo((short) 2);
                });
    }

    @Test void singleBrokerDefaultsIncludeOnePartitionDeadLetterTopic() {
        context.run(ctx -> {
            assertThat(ctx.getBeansOfType(NewTopic.class)).hasSize(2);
            for (NewTopic topic : ctx.getBeansOfType(NewTopic.class).values()) {
                assertThat(topic.numPartitions()).isEqualTo(1);
                assertThat(topic.replicationFactor()).isEqualTo((short) 1);
            }
        });
    }

}
