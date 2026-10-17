package com.itms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.entity.*;
import com.itms.repository.*;
import com.itms.service.*;
import com.itms.security.Passwords;
import com.itms.util.JwtUtil;
import com.itms.service.impl.TrafficSimulatorServiceImpl;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.kafka.common.KafkaException;
import static org.mockito.Mockito.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "itms.traffic.simulator.enabled=false","spring.kafka.listener.auto-startup=false",
    "spring.kafka.admin.auto-create=false","spring.jpa.hibernate.ddl-auto=update",
    "spring.datasource.username=postgres","spring.datasource.password=","spring.data.redis.database=1",
    "itms.demo.reset-default-users=false"
})
class TrafficEventIdPostgresIT {
    @DynamicPropertySource static void services(DynamicPropertyRegistry r) {
        assertThat(System.getProperty("test.postgres.isolated")).isEqualTo("true");
        assertThat(System.getProperty("test.redis.isolated")).isEqualTo("true");
        int pg=Integer.parseInt(System.getProperty("test.postgres.port"));
        int redis=Integer.parseInt(System.getProperty("test.redis.port"));
        assertThat(pg).isBetween(1024,65535); assertThat(redis).isBetween(1024,65535);
        r.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+pg+"/itms_concurrency_it");
        r.add("spring.data.redis.port",()->redis);
    }
    @Autowired TestRestTemplate http;
    @Autowired TrafficDataRepository data;
    @Autowired TrafficDataService traffic;
    @Autowired TrafficAlertRepository alerts;
    @Autowired TrafficIngestionReceiptRepository receipts;
    @Autowired TrafficIngestionService ingestion;
    @Autowired UserRepository users;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redis;
    HttpHeaders headers;
    @BeforeEach void clean() {
        receipts.deleteAll(); alerts.deleteAll(); data.deleteAll();
        redis.delete(List.of("itms:traffic:realtime","itms:traffic:intersection-summary"));
        var user=users.findByUsername("event-ingestion-operator").orElseGet(()->users.save(User.builder()
                .username("event-ingestion-operator").password(Passwords.encode("test-pass-123"))
                .role("TRAFFIC_MANAGER").enabled(true).build()));
        headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwt.generateToken(user.getId(),user.getUsername(),user.getRole(),user.getAuthVersion()));
    }
    Map<String,Object> payload(String id,int count) {
        var p=new LinkedHashMap<String,Object>(); p.put("eventId",id);p.put("intersectionId","INT-001");
        p.put("direction","NORTH");p.put("vehicleCount",count);p.put("avgSpeed",12.0);
        p.put("dataSource","SIMULATOR");p.put("congestionLevel",3);return p;
    }
    ResponseEntity<Map> save(Map<String,Object> p) {
        return http.postForEntity("/api/traffic/save",new HttpEntity<>(p,headers),Map.class);
    }
    @Test void sameEventAcrossHttpAndKafkaOffsetsCreatesOneRowAndOneAlert() throws Exception {
        var payload=payload("same-event-http-kafka",900);
        var first=save(payload);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        traffic.getRealTimeData();
        assertThat(redis.hasKey("itms:traffic:realtime")).isTrue();
        var second=save(payload);assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(redis.hasKey("itms:traffic:realtime")).isTrue();
        assertThat(((Map<?,?>)first.getBody().get("data")).get("id")).isEqualTo(((Map<?,?>)second.getBody().get("data")).get("id"));
        String json=mapper.writeValueAsString(payload);
        ingestion.ingest(new ConsumerRecord<>("business-events",0,10,"INT-001:NORTH",json));
        ingestion.ingest(new ConsumerRecord<>("business-events",0,11,"INT-001:NORTH",json));
        assertThat(data.count()).isEqualTo(1);assertThat(alerts.count()).isEqualTo(1);assertThat(receipts.count()).isEqualTo(2);
    }
    @Test void sameEventWithChangedContentReturns409AndForgedHashIsIgnored() {
        var firstPayload=payload("conflict-event",900); firstPayload.put("payloadHash","forged");
        var first=save(firstPayload);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?,?>)first.getBody().get("data")).containsKey("payloadHash")).isFalse();
        var conflict=payload("conflict-event",901);conflict.put("payloadHash","forged");
        assertThat(save(conflict).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(data.findAll()).singleElement().satisfies(row->assertThat(row.getVehicleCount()).isEqualTo(900));
    }
    @Test void concurrentHttpReplaysReturnOneStoredRecord() throws Exception {
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);var ready=new CountDownLatch(8);
        var jobs=new ArrayList<Future<ResponseEntity<Map>>>();
        try {
            for(int n=0;n<8;n++) jobs.add(pool.submit(()->{ready.countDown();start.await();return save(payload("concurrent-event",900));}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            var ids=new HashSet<Object>();
            for(var job:jobs) {var result=job.get(15,TimeUnit.SECONDS);assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);ids.add(((Map<?,?>)result.getBody().get("data")).get("id"));}
            assertThat(ids).hasSize(1);assertThat(data.count()).isEqualTo(1);
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void batchConflictRollsBackEarlierRowsAndLeavesExistingEventUntouched() {
        assertThat(save(payload("z-existing",900)).getStatusCode()).isEqualTo(HttpStatus.OK);
        traffic.getRealTimeData();
        var result=http.postForEntity("/api/traffic/batch-save",new HttpEntity<>(List.of(payload("a-new",100),payload("z-existing",901)),headers),Map.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(data.count()).isEqualTo(1);
        assertThat(data.findAll().get(0).getVehicleCount()).isEqualTo(900);
        assertThat(redis.hasKey("itms:traffic:realtime")).isTrue();
    }
    @Test void newRequestsWithoutEventIdReceiveDistinctIdsAndInvalidEventIdIsRejected() {
        var request=payload("unused",100);request.remove("eventId");
        var first=save(request);var second=save(request);
        var a=((Map<?,?>)first.getBody().get("data")).get("eventId");var b=((Map<?,?>)second.getBody().get("data")).get("eventId");
        assertThat(a).isNotNull().isNotEqualTo(b);
        assertThat(save(payload(" ",100)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(save(payload("x".repeat(65),100)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test @SuppressWarnings("unchecked") void synchronousFallbackAndLateKafkaDeliveryShareTheSameBusinessEvent() throws Exception {
        var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);
        List<String> attempted=new ArrayList<>();
        when(kafka.send(anyString(),anyString(),anyString())).thenAnswer(invocation->{
            attempted.add(invocation.getArgument(2));throw new KafkaException("simulated ambiguous synchronous timeout");
        });
        var simulator=new TrafficSimulatorServiceImpl(kafka,mapper,traffic);
        ReflectionTestUtils.setField(simulator,"topic","traffic-data");
        assertThat(simulator.generateOnce()).hasSize(56);
        assertThat(attempted).hasSize(1);assertThat(data.count()).isEqualTo(56);
        ingestion.ingest(new ConsumerRecord<>("business-events",0,1000,"INT-001:NORTH",attempted.get(0)));
        assertThat(data.count()).isEqualTo(56);assertThat(receipts.count()).isEqualTo(1);
        assertThat(data.findAll()).allSatisfy(row->assertThat(row.getDataSource()).isEqualTo("SIMULATOR"));
    }

    @Test void concurrentReversedBatchesKeepInputOrderingAndDeduplicate() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        var a=payload("batch-a",900);var b=payload("batch-b",901);b.put("direction","SOUTH");
        try {
            var forward=pool.submit(()->{start.await();return http.postForEntity("/api/traffic/batch-save",new HttpEntity<>(List.of(a,b,a),headers),Map.class);});
            var reverse=pool.submit(()->{start.await();return http.postForEntity("/api/traffic/batch-save",new HttpEntity<>(List.of(b,a),headers),Map.class);});
            start.countDown();var first=forward.get(15,TimeUnit.SECONDS);var second=reverse.get(15,TimeUnit.SECONDS);
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
            var firstRows=(List<Map<String,Object>>)first.getBody().get("data");var secondRows=(List<Map<String,Object>>)second.getBody().get("data");
            assertThat(firstRows).extracting(row->row.get("eventId")).containsExactly("batch-a","batch-b","batch-a");
            assertThat(secondRows).extracting(row->row.get("eventId")).containsExactly("batch-b","batch-a");
            assertThat(firstRows.get(0).get("id")).isEqualTo(firstRows.get(2).get("id"));
            assertThat(data.count()).isEqualTo(2);
        }finally{start.countDown();pool.shutdownNow();}
    }

}
