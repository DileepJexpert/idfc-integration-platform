package com.idfcfirstbank.integration.fullflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.OriginationJourneyApplication;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * THE WORKSTREAM-A SEAM, LIVE: a journey a maker/checker publishes through the
 * REAL journey-registry (HTTP, localhost:8104) is what the REAL engine app
 * boots on and runs (embedded Kafka carries the traffic; this test plays the
 * capability fleet). The two named live checks the A4 gate demanded:
 *
 * <ol>
 *   <li><b>Version pinning across a MID-RUN publish</b>: v2 publishes while a
 *       v1 run is parked awaiting a capability — the in-flight run completes on
 *       v1 (never dispatches the v2-only node) and the NEXT run starts on v2;</li>
 *   <li><b>Bootstrap-down is deliberate</b>: with the registry unreachable the
 *       engine REFUSES to start, loudly (no infra needed — always runs).</li>
 * </ol>
 *
 * <p>SELF-SKIPPING when the registry isn't running (start it per
 * {@code docs/testing/REGISTRY_RUNBOOK.md}); the compose stack wires the same
 * seam for operators.
 */
class RegistryEngineSeamIT {

    private static final String REGISTRY = "http://localhost:8104";
    private static final String TOKEN = "dev-registry-token";
    private static final String ORIG_TOPIC = "seam.orig.v1";
    private static final String CAP_REQUEST = "cap.seamcap.request.v1";
    private static final String CAP_RESPONSE = "cap.seamcap.response.v1";
    private static final String EXTRA_REQUEST = "cap.seamextra.request.v1";
    private static final String DECISION_TOPIC = "orig.decision.v1";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Started lazily by the seam test (the bootstrap-down test needs no broker). */
    private static org.springframework.kafka.test.EmbeddedKafkaKraftBroker kafka;

    @AfterAll
    static void stopKafka() {
        if (kafka != null) {
            kafka.destroy();
        }
    }

    // ---------------------------------------------------------------------------
    // 1. The pinning loop, live
    // ---------------------------------------------------------------------------

    @Test
    void designerPublishedJourneyRunsAndPinsAcrossAMidRunPublish() throws Exception {
        assumeTrue(registryUp(), "journey-registry not reachable on " + REGISTRY
                + " — start it (docs/testing/REGISTRY_RUNBOOK.md) and rerun");

        kafka = new org.springframework.kafka.test.EmbeddedKafkaKraftBroker(
                1, 1, ORIG_TOPIC, CAP_REQUEST, CAP_RESPONSE, EXTRA_REQUEST, DECISION_TOPIC);
        kafka.afterPropertiesSet();
        String brokers = kafka.getBrokersAsString();

        // ---- the DESIGNER side: maker authors v1, checker publishes it --------
        String key = "seam-" + System.currentTimeMillis();
        RestClient registry = RestClient.builder()
                .baseUrl(REGISTRY)
                .defaultHeader("X-Registry-Token", TOKEN)
                .build();
        createJourney(registry, key);
        publishVersion(registry, key, 1, v1Config(key));

        // ---- the ENGINE side: boots FROM the registry ---------------------------
        ConfigurableApplicationContext engine = new SpringApplicationBuilder(
                OriginationJourneyApplication.class)
                .properties(
                        "server.port=0",
                        // full-flow-it's classpath carries OTHER services' JDBC
                        // starter; the engine app itself has no datasource.
                        "spring.autoconfigure.exclude=org.springframework.boot"
                                + ".autoconfigure.jdbc.DataSourceAutoConfiguration",
                        "spring.kafka.bootstrap-servers=" + brokers,
                        "idfc.engine.journey-source=registry",
                        "idfc.engine.registry.base-url=" + REGISTRY,
                        "idfc.engine.registry.auth-token=" + TOKEN,
                        "idfc.engine.registry.refresh-seconds=1",
                        "idfc.engine.origination-topics=" + ORIG_TOPIC,
                        "idfc.engine.origination-group=seam-engine",
                        "idfc.engine.response-group=seam-engine-responses",
                        "idfc.engine.type-to-journey.SEAM=" + key,
                        "idfc.engine.state-store=in-memory")
                .run();

        List<JsonNode> capRequests = new CopyOnWriteArrayList<>();
        List<JsonNode> extraRequests = new CopyOnWriteArrayList<>();
        List<JsonNode> decisions = new CopyOnWriteArrayList<>();
        AtomicBoolean pumping = new AtomicBoolean(true);
        Thread pump = startPump(brokers, pumping, capRequests, extraRequests, decisions);

        try (KafkaProducer<String, String> producer = producer(brokers)) {
            // ---- run 1 starts while v1 is current — and PARKS at the capability
            send(producer, ORIG_TOPIC, envelope("corr-seam-1", "APP-SEAM-1"));
            await().atMost(Duration.ofSeconds(30)).until(() -> !capRequests.isEmpty());
            JsonNode run1Request = capRequests.get(0);
            String run1 = run1Request.get("journeyInstanceId").asText();

            // ---- MID-RUN, the checker publishes v2 (adds the seamextra node) ----
            publishVersion(registry, key, 2, v2Config(key));
            Thread.sleep(3_000); // > refresh-seconds: the engine snapshot has moved

            // ---- the parked v1 run resumes: completes on ITS version ------------
            send(producer, CAP_RESPONSE, response(run1Request));
            await().atMost(Duration.ofSeconds(30)).until(() -> decisions.stream()
                    .anyMatch(d -> run1.equals(d.get("journeyInstanceId").asText())));
            assertThat(extraRequests)
                    .as("the pinned v1 run must NEVER dispatch the v2-only node")
                    .noneMatch(r -> run1.equals(r.get("journeyInstanceId").asText()));

            // ---- the NEXT run starts on v2: its verify hop leads to seamextra ---
            send(producer, ORIG_TOPIC, envelope("corr-seam-2", "APP-SEAM-2"));
            await().atMost(Duration.ofSeconds(30)).until(() -> capRequests.stream()
                    .anyMatch(r -> !run1.equals(r.get("journeyInstanceId").asText())));
            JsonNode run2Request = capRequests.stream()
                    .filter(r -> !run1.equals(r.get("journeyInstanceId").asText()))
                    .findFirst().orElseThrow();
            String run2 = run2Request.get("journeyInstanceId").asText();
            send(producer, CAP_RESPONSE, response(run2Request));

            await().atMost(Duration.ofSeconds(30)).until(() -> extraRequests.stream()
                    .anyMatch(r -> run2.equals(r.get("journeyInstanceId").asText())));
            assertThat(decisions)
                    .as("run 2 is mid-v2-graph (parked at seamextra), not decided")
                    .noneMatch(d -> run2.equals(d.get("journeyInstanceId").asText()));
        } finally {
            pumping.set(false);
            pump.join(5_000);
            engine.close();
        }
    }

    // ---------------------------------------------------------------------------
    // 2. Bootstrap-down refuses to start (always runs — no infra needed)
    // ---------------------------------------------------------------------------

    @Test
    void engineRefusesToStartWhenTheRegistryIsUnreachable() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(OriginationJourneyApplication.class)
                .properties(
                        "server.port=0",
                        "spring.autoconfigure.exclude=org.springframework.boot"
                                + ".autoconfigure.jdbc.DataSourceAutoConfiguration",
                        // nothing listens here — connection refused at bootstrap
                        "idfc.engine.journey-source=registry",
                        "idfc.engine.registry.base-url=http://localhost:59997",
                        "idfc.engine.registry.auth-token=any",
                        "idfc.engine.registry.connect-timeout-ms=500",
                        "idfc.engine.registry.read-timeout-ms=500",
                        // never reached — bootstrap fails first
                        "spring.kafka.bootstrap-servers=localhost:59998",
                        "idfc.engine.state-store=in-memory")
                .run()
                .close())
                .hasStackTraceContaining("refusing to start");
    }

    // ---------------------------------------------------------------------------
    // registry ops (the designer's HTTP verbs, maker + checker actors)
    // ---------------------------------------------------------------------------

    private static void createJourney(RestClient registry, String key) {
        registry.post().uri("/api/v1/journeys")
                .header("X-User-Id", "maker-asha")
                .body(Map.of("key", key, "name", "Seam IT", "businessLine", "PL"))
                .retrieve().toBodilessEntity();
    }

    private static void publishVersion(RestClient registry, String key, int version,
                                       Map<String, Object> config) {
        registry.post().uri("/api/v1/journeys/{key}/versions", key)
                .header("X-User-Id", "maker-asha")
                .body(Map.of("config", config, "note", "seam v" + version))
                .retrieve().toBodilessEntity();
        registry.post().uri("/api/v1/journeys/{key}/versions/{v}/submit", key, version)
                .header("X-User-Id", "maker-asha")
                .retrieve().toBodilessEntity();
        registry.post().uri("/api/v1/journeys/{key}/versions/{v}/approve", key, version)
                .header("X-User-Id", "checker-vikram")
                .retrieve().toBodilessEntity();
    }

    /** v1: verify -> done. */
    private static Map<String, Object> v1Config(String key) {
        return Map.of(
                "journeyKey", key, "version", 1, "startNodeId", "n_verify",
                "nodes", List.of(
                        Map.of("id", "n_verify", "type", "task", "capability", "seamcap",
                                "operation", "check", "output", "context.seam",
                                "next", List.of("n_done")),
                        Map.of("id", "n_done", "type", "terminal", "status", "completed")));
    }

    /** v2: verify -> EXTRA (seamextra) -> done — a v2 resume is VISIBLE. */
    private static Map<String, Object> v2Config(String key) {
        return Map.of(
                "journeyKey", key, "version", 2, "startNodeId", "n_verify",
                "nodes", List.of(
                        Map.of("id", "n_verify", "type", "task", "capability", "seamcap",
                                "operation", "check", "output", "context.seam",
                                "next", List.of("n_extra")),
                        Map.of("id", "n_extra", "type", "task", "capability", "seamextra",
                                "operation", "enrich", "output", "context.extra",
                                "next", List.of("n_done")),
                        Map.of("id", "n_done", "type", "terminal", "status", "completed")));
    }

    // ---------------------------------------------------------------------------
    // kafka plumbing (this test IS the capability fleet + decision listener)
    // ---------------------------------------------------------------------------

    private static String envelope(String correlationId, String applicationRef) throws Exception {
        return JSON.writeValueAsString(Map.of(
                "type", "SEAM",
                "correlationId", correlationId,
                "applicationRef", applicationRef,
                "source", "SFDC",
                "payload", Map.of("seam", true)));
    }

    /** An OK CapabilityResponse echoing the request's routing identity. */
    private static String response(JsonNode request) throws Exception {
        return JSON.writeValueAsString(Map.of(
                "journeyInstanceId", request.get("journeyInstanceId").asText(),
                "correlationId", request.get("correlationId").asText(),
                "nodeId", request.get("nodeId").asText(),
                "capabilityKey", request.get("capabilityKey").asText(),
                "status", "OK",
                "result", Map.of("checked", true)));
    }

    private static Thread startPump(String brokers, AtomicBoolean pumping,
                                    List<JsonNode> capRequests, List<JsonNode> extraRequests,
                                    List<JsonNode> decisions) {
        Thread pump = new Thread(() -> {
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "seam-test-observer");
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                consumer.subscribe(List.of(CAP_REQUEST, EXTRA_REQUEST, DECISION_TOPIC));
                while (pumping.get()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                    for (ConsumerRecord<String, String> record : records) {
                        JsonNode node = parse(record.value());
                        switch (record.topic()) {
                            case CAP_REQUEST -> capRequests.add(node);
                            case EXTRA_REQUEST -> extraRequests.add(node);
                            case DECISION_TOPIC -> decisions.add(node);
                            default -> { }
                        }
                    }
                }
            }
        }, "seam-observer");
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable seam message", e);
        }
    }

    private static KafkaProducer<String, String> producer(String brokers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(props);
    }

    private static void send(KafkaProducer<String, String> producer, String topic, String value)
            throws Exception {
        producer.send(new ProducerRecord<>(topic, value)).get();
    }

    private static boolean registryUp() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(REGISTRY + "/actuator/health").openConnection();
            conn.setConnectTimeout(2_000);
            conn.setReadTimeout(2_000);
            return conn.getResponseCode() == HttpStatus.OK.value();
        } catch (Exception e) {
            return false;
        }
    }
}
