package com.itms.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic trafficDataTopic(
            @Value("${itms.kafka.topic.traffic-data:traffic-data}") String topic,
            @Value("${itms.kafka.topic.partitions:1}") int partitions,
            @Value("${itms.kafka.topic.replicas:1}") int replicas) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic trafficDataDeadLetterTopic(
            @Value("${itms.kafka.topic.traffic-data:traffic-data}") String topic,
            @Value("${itms.kafka.topic.partitions:1}") int partitions,
            @Value("${itms.kafka.topic.replicas:1}") int replicas) {
        return TopicBuilder.name(topic + ".DLT").partitions(partitions).replicas(replicas).build();
    }
}
