package com.idfcfirstbank.integration.capabilities.lending.origination;

import com.idfcfirstbank.integration.capabilities.lending.origination.domain.model.LoanBooking;
import com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.FinnOneBookingPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE FinnOne booking redelivery test — the scenario that costs real money.
 *
 * <p>Kafka is at-least-once: the SAME booking request (same {@code idempotencyKey}
 * = journeyInstanceId:nodeId) WILL be delivered more than once — a consumer crash
 * after the stored-proc call but before the offset commit, a rebalance, an engine
 * redelivery. The FinnOne stored procedure (SP_FINNONE_SUBMISSION) books a loan;
 * re-executing it books the loan TWICE.
 *
 * <p>This test drives the REAL application context over a REAL (embedded, in-JVM)
 * broker: the same request JSON is delivered twice to
 * {@code cap.lending-origination.request.v1}; both deliveries must produce an OK
 * response with the SAME loanId, and FinnOne must have been invoked EXACTLY ONCE.
 */
@SpringBootTest(
        classes = {LendingOriginationApplication.class, FinnOneBookingRedeliveryTest.CountingFinnOneConfig.class},
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 1, topics = {
        FinnOneBookingRedeliveryTest.REQUEST_TOPIC,
        FinnOneBookingRedeliveryTest.RESPONSE_TOPIC,
        FinnOneBookingRedeliveryTest.REQUEST_TOPIC + ".dlq",
})
class FinnOneBookingRedeliveryTest {

    static final String REQUEST_TOPIC = "cap.lending-origination.request.v1";
    static final String RESPONSE_TOPIC = "cap.lending-origination.response.v1";

    /** Counts every booking that reaches "FinnOne" — the money meter. */
    @TestConfiguration
    static class CountingFinnOneConfig {
        static final AtomicInteger BOOKINGS = new AtomicInteger();

        @Bean
        @Primary
        FinnOneBookingPort countingFinnOne() {
            return application -> {
                BOOKINGS.incrementAndGet();
                return new LoanBooking("LN-" + application.getOrDefault("applicationRef", "REF"), "BOOKED");
            };
        }
    }

    private static final String REQUEST_JSON = """
            {
              "journeyInstanceId": "ji-redelivery-1",
              "correlationId": "corr-redelivery-1",
              "capabilityKey": "lending-origination",
              "nodeId": "n_book",
              "payload": {"applicationRef": "APP-REDELIVERY", "amount": 250000},
              "collectedResults": {},
              "operation": "book",
              "idempotencyKey": "ji-redelivery-1:n_book"
            }""";

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Timeout(120)
    void redeliveredBookingRequestBooksTheLoanExactlyOnce() throws Exception {
        // Deliver the SAME request twice (at-least-once redelivery).
        Properties producerProps = new Properties();
        producerProps.putAll(KafkaTestUtils.producerProps(broker));
        producerProps.put("key.serializer", StringSerializer.class.getName());
        producerProps.put("value.serializer", StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
            producer.send(new ProducerRecord<>(REQUEST_TOPIC, "ji-redelivery-1", REQUEST_JSON)).get();
            producer.send(new ProducerRecord<>(REQUEST_TOPIC, "ji-redelivery-1", REQUEST_JSON)).get();
        }

        // Both deliveries must be answered (a redelivery is acknowledged, not dropped)...
        List<ConsumerRecord<String, String>> responses = consumeResponses(2, Duration.ofSeconds(60));
        assertThat(responses).hasSize(2);

        List<String> loanIds = new ArrayList<>();
        for (ConsumerRecord<String, String> r : responses) {
            Map<?, ?> response = objectMapper.readValue(r.value(), Map.class);
            assertThat(response.get("status")).as("both responses OK").isEqualTo("OK");
            Map<?, ?> result = (Map<?, ?>) response.get("result");
            loanIds.add(String.valueOf(result.get("loanId")));
        }
        assertThat(loanIds).as("same loan on both responses").containsExactly(
                "LN-APP-REDELIVERY", "LN-APP-REDELIVERY");

        // ...but the LOAN IS BOOKED EXACTLY ONCE. This is the money assertion:
        // a redelivered request must NOT re-execute SP_FINNONE_SUBMISSION.
        assertThat(CountingFinnOneConfig.BOOKINGS.get())
                .as("FinnOne stored proc executed exactly once for the redelivered request")
                .isEqualTo(1);
    }

    private List<ConsumerRecord<String, String>> consumeResponses(int expected, Duration timeout) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("finnone-redelivery-test", "true", broker);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer =
                     new DefaultKafkaConsumerFactory<>(props, new org.apache.kafka.common.serialization.StringDeserializer(),
                             new org.apache.kafka.common.serialization.StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, RESPONSE_TOPIC);
            List<ConsumerRecord<String, String>> collected = new ArrayList<>();
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (collected.size() < expected && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    collected.add(r);
                }
            }
            return collected;
        }
    }
}
