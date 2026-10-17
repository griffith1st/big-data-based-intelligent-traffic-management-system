package com.itms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itms.entity.TrafficData;
import com.itms.service.TrafficDataService;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrafficSimulatorDeliveryTest {
    @Test @SuppressWarnings("unchecked") void synchronousSendFailurePersistsEveryGeneratedEventWithStableIdentity() throws Exception {
        var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);var db=mock(TrafficDataService.class);
        var mapper=new ObjectMapper().findAndRegisterModules();var service=new TrafficSimulatorServiceImpl(kafka,mapper,db);
        ReflectionTestUtils.setField(service,"topic","traffic-data");
        when(kafka.send(anyString(),anyString(),anyString())).thenThrow(new KafkaException("synchronous broker failure"));
        when(db.saveTrafficData(any())).thenAnswer(i->i.getArgument(0));
        var batch=service.generateOnce();
        assertThat(batch).hasSize(56).allSatisfy(row->assertThat(row.getDataSource()).isEqualTo("SIMULATOR"));
        var sent=org.mockito.ArgumentCaptor.forClass(String.class);verify(kafka).send(anyString(),anyString(),sent.capture());
        var ids=new HashSet<String>();
        for(String payload:sent.getAllValues()) {String id=mapper.readTree(payload).path("eventId").asText();assertThat(id).isNotBlank();ids.add(id);}
        assertThat(ids).hasSize(1);
        var saved=org.mockito.ArgumentCaptor.forClass(TrafficData.class);verify(db,times(56)).saveTrafficData(saved.capture());
        var allIds=new HashSet<String>();
        for(var row:saved.getAllValues()) allIds.add(mapper.valueToTree(row).path("eventId").asText());
        assertThat(allIds).hasSize(56).containsAll(ids);
    }
    @Test @SuppressWarnings("unchecked") void asyncFailureFallsBackUsingTheSameEventPublishedToKafka() throws Exception {
        var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);var db=mock(TrafficDataService.class);
        var mapper=new ObjectMapper().findAndRegisterModules();var service=new TrafficSimulatorServiceImpl(kafka,mapper,db);
        ReflectionTestUtils.setField(service,"topic","traffic-data");
        List<CompletableFuture<SendResult<String,String>>> pending=new ArrayList<>();List<String> payloads=new ArrayList<>();
        when(kafka.send(anyString(),anyString(),anyString())).thenAnswer(i->{payloads.add(i.getArgument(2));var f=new CompletableFuture<SendResult<String,String>>();pending.add(f);return f;});
        service.generateOnce();verifyNoInteractions(db);
        pending.get(0).completeExceptionally(new KafkaException("late acknowledgement timeout"));
        var saved=org.mockito.ArgumentCaptor.forClass(TrafficData.class);verify(db).saveTrafficData(saved.capture());
        assertThat(mapper.valueToTree(saved.getValue()).path("eventId").asText()).isNotBlank().isEqualTo(mapper.readTree(payloads.get(0)).path("eventId").asText());
    }
}
