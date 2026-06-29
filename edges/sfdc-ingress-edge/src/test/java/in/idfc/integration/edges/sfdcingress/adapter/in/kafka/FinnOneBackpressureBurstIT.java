package in.idfc.integration.edges.sfdcingress.adapter.in.kafka;

import in.idfc.integration.edges.sfdcingress.domain.port.FinnOneMeterPort;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Backpressure harness 10x-burst test (§G), against a REAL Kafka. Proves:
 * a burst far larger than the cap N is absorbed as Kafka consumer lag, FinnOne
 * concurrency NEVER exceeds N, and the backlog drains to zero. The mock proc
 * hard-fails if concurrency exceeds N, so a regression cannot pass silently.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FinnOneBackpressureBurstIT {

    private static final int CAP_N = 4;
    private static final String TOPIC = "orig.sfdc.pl.v1";
    private static final int BURST = 10 * CAP_N; // 10x the cap

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("idfc.edge.finnone.max-concurrency", () -> CAP_N);
        registry.add("idfc.edge.finnone.consume-topics", () -> TOPIC);
        // Keep the edge's Aerospike bean from hard-failing context startup; this
        // test exercises only the Kafka consumer path.
        registry.add("idfc.aerospike.host", () -> "localhost");
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private FinnOneMeterPort finnOne;

    @Test
    void tenXBurst_neverExceedsCap_andDrainsToZero() {
        for (int i = 0; i < BURST; i++) {
            // Spread across all N partitions explicitly so all N consumer threads
            // engage (the default sticky partitioner would otherwise concentrate a
            // fast null-key burst onto one partition).
            kafkaTemplate.send(TOPIC, i % CAP_N, "k" + i, "{\"applicationRef\":\"APP-" + i + "\"}");
        }
        kafkaTemplate.flush();

        // Backlog drains: every burst message is eventually processed exactly once.
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(finnOne.totalInvocations()).isEqualTo(BURST));

        assertThat(finnOne.maxObservedConcurrency())
                .as("FinnOne concurrency never exceeds the cap N")
                .isLessThanOrEqualTo(CAP_N);
        assertThat(finnOne.maxObservedConcurrency())
                .as("the burst actually drove concurrency (backpressure, not serialization)")
                .isGreaterThan(1);
    }
}
