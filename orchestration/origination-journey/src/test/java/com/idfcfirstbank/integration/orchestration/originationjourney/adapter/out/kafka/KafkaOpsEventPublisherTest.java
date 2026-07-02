package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.OpsEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B.2 delivery discipline: the send is CONFIRMED (a failure is known, logged,
 * counted) but NEVER fatal — observability being down must not fail a loan hop.
 * And the wire shape is ids-only: no payload key can exist in the JSON.
 */
class KafkaOpsEventPublisherTest {

    /** Production hands the publisher Spring's mapper (jsr310 registered). */
    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static OpsEvent sample() {
        JourneyInstance i = new JourneyInstance("ji-ev-1", "corr-ev", "loan-origination", 2,
                "APP-1", Map.of("pan", "ABCDE1234F"));
        return OpsEvent.run(OpsEvent.RUN_STARTED, i, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBrokerFailureIsCountedAndSwallowedNeverThrown() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        KafkaOpsEventPublisher publisher =
                new KafkaOpsEventPublisher(template, mapper(), "ops.journey.events.v1");

        assertThatCode(() -> publisher.emit(sample()))
                .as("a run must never fail because observability is down")
                .doesNotThrowAnyException();
        assertThat(publisher.droppedCount()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWireShapeIsIdsOnly() throws Exception {
        List<String> sent = new ArrayList<>();
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            sent.add(inv.getArgument(2));
            SendResult<String, String> result = new SendResult<>(
                    new ProducerRecord<>("ops.journey.events.v1", "k", "v"), null);
            return CompletableFuture.completedFuture(result);
        });
        KafkaOpsEventPublisher publisher =
                new KafkaOpsEventPublisher(template, mapper(), "ops.journey.events.v1");

        publisher.emit(sample());

        JsonNode json = new ObjectMapper().readTree(sent.get(0));
        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "event", "journeyInstanceId", "journeyKey", "journeyVersion",
                "nodeId", "outcome", "correlationId", "at");
        assertThat(json.toString())
                .as("the applicant payload can never ride an ops event")
                .doesNotContain("ABCDE1234F")
                .doesNotContain("payload");
    }
}
