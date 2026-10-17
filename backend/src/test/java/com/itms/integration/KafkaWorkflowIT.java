package com.itms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.entity.TrafficData;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Real broker -> listener -> PostgreSQL/Redis, including poison record recovery. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "itms.traffic.simulator.enabled=false", "spring.datasource.username=postgres",
        "spring.datasource.password=", "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.data.redis.database=4", "itms.kafka.consumer.retry-interval-ms=50"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaWorkflowIT {
    static final String TOPIC="itms-workflow-it-"+UUID.randomUUID();
    static final String GROUP=TOPIC+"-group";
    static String bootstrap;

    @DynamicPropertySource static void dependencies(DynamicPropertyRegistry r) {
        assertThat(System.getProperty("test.postgres.isolated")).isEqualTo("true");
        assertThat(System.getProperty("test.redis.isolated")).isEqualTo("true");
        assertThat(System.getProperty("test.kafka.isolated")).isEqualTo("true");
        int pg=Integer.parseInt(System.getProperty("test.postgres.port"));
        int redis=Integer.parseInt(System.getProperty("test.redis.port"));
        int kafka=Integer.parseInt(System.getProperty("test.kafka.port"));
        assertThat(pg).isBetween(1024,65535);
        assertThat(redis).isBetween(1024,65535);
        assertThat(kafka).isBetween(1024,65535);
        bootstrap="127.0.0.1:"+kafka;
        r.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+pg+"/itms_kafka_it");
        r.add("spring.data.redis.host",()->"127.0.0.1");
        r.add("spring.data.redis.port",()->redis);
        r.add("spring.kafka.bootstrap-servers",()->bootstrap);
        r.add("spring.kafka.consumer.group-id",()->GROUP);
        r.add("itms.kafka.topic.traffic-data",()->TOPIC);
    }

    @Autowired KafkaTemplate<String,String> producer;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;

    private String event(int vehicles) throws Exception {
        return mapper.writeValueAsString(TrafficData.builder().intersectionId("INT-001")
                .direction("NORTH").vehicleCount(vehicles).avgSpeed(45.0)
                .recordTime(LocalDateTime.now()).dataSource("TEST").build());
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM traffic_data", Integer.class);
    }

    private void awaitCondition(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test void validTrafficCommitsAndPoisonRecordMovesToDltBeforeConsumptionContinues() throws Exception {
        jdbc.update("DELETE FROM traffic_ingestion_receipts");
        jdbc.update("DELETE FROM traffic_data");
        producer.send(TOPIC,"INT-001:NORTH",event(600)).get(15,TimeUnit.SECONDS);
        awaitCondition(()->rows()==1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM traffic_ingestion_receipts",Integer.class)).isEqualTo(1);

        String poison="{broken-json";
        var failed=producer.send(TOPIC,"INT-001:NORTH",poison).get(15,TimeUnit.SECONDS).getRecordMetadata();
        var config=Map.<String,Object>of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG,TOPIC+"-observer",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        try(var observer=new KafkaConsumer<String,String>(config)) {
            observer.assign(List.of(new TopicPartition(TOPIC+".DLT",0)));
            observer.seekToBeginning(observer.assignment());
            boolean found=false;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!found && System.nanoTime()<deadline) {
                for(var record:observer.poll(Duration.ofMillis(200))) {
                    if(poison.equals(record.value())) {
                        assertThat(new String(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                                StandardCharsets.UTF_8)).isEqualTo(TOPIC);
                        found=true;
                    }
                }
            }
            assertThat(found).as("poison record must reach the real dead-letter topic").isTrue();
        }
        producer.send(TOPIC,"INT-001:NORTH",event(700)).get(15,TimeUnit.SECONDS);
        awaitCondition(()->rows()==2);
        assertThat(jdbc.queryForObject("SELECT sum(vehicle_count) FROM traffic_data",Integer.class)).isEqualTo(1300);
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",bootstrap))) {
            awaitCondition(()->{
                try {
                    var offsets=admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                            .get(3,TimeUnit.SECONDS);
                    var offset=offsets.get(new TopicPartition(TOPIC,0));
                    return offset!=null && offset.offset()>failed.offset();
                } catch(Exception ex) { return false; }
            });
        }
    }

    @AfterAll void removeOwnTopics() throws Exception {
        listeners.stop();
        if(bootstrap!=null) try(var admin=AdminClient.create(Map.of("bootstrap.servers",bootstrap))) {
            admin.deleteTopics(List.of(TOPIC,TOPIC+".DLT")).all().get(15,TimeUnit.SECONDS);
        }
    }
}
