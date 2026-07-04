package com.idfcfirstbank.integration.fullflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.devicefinancing.DeviceFinancingApplication;
import com.idfcfirstbank.integration.capabilities.fusionhcm.FusionHcmApplication;
import com.idfcfirstbank.integration.edges.filebatch.FileBatchEdgeApplication;
import com.idfcfirstbank.integration.orchestration.originationjourney.OriginationJourneyApplication;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * THE LEGACY-PATTERNS DEMO, END-TO-END: the three demo behaviours run against
 * the REAL engine over embedded Kafka — exactly what the live demo shows,
 * asserted:
 *
 * <ol>
 *   <li><b>Brand-as-config</b>: ONE device-financing journey; SAMSUNG
 *       validates+blocks, GODREJ blocks only, a declined device is a teal
 *       completion, a vendor failure is a red FAILED run — every difference a
 *       config row. HISENSE FAILS CLOSED until its row exists, then a restart
 *       with two CLI rows (no rebuild) makes it a first-class brand.</li>
 *   <li><b>File-batch scaffold</b>: a CSV dropped in a folder becomes one run
 *       per record grouped by a batch correlationId — 4 succeed, the crafted
 *       bad row fails with a CLASS; re-drops are refused twice over (ledger,
 *       then engine dedup even with the ledger wiped).</li>
 *   <li><b>Ops window</b>: every assertion above is made THROUGH the audited
 *       /ops API — the demo's "you can watch it" claim, verified.</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyPatternsDemoIT {

    private static final String DEVICE_TOPIC = "orig.demo.device.v1";
    private static final String HR_TOPIC = "orig.demo.hr.v1";
    private static final String OPS_TOKEN = "demo-ops-token";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static EmbeddedKafkaKraftBroker kafka;
    private static String brokers;
    private static HttpServer vendorStub;   // the mocked-DATA boundary (Docker-free)
    private static String vendorBase;       // http://127.0.0.1:{port}
    private static ConfigurableApplicationContext engine;
    private static ConfigurableApplicationContext deviceCapability;
    private static ConfigurableApplicationContext fusionCapability;
    private static ConfigurableApplicationContext fileBatchEdge;
    private static KafkaProducer<String, String> producer;
    private static RestClient ops;

    @TempDir
    static Path inbox;

    // ---------------------------------------------------------------------------
    // Boot: engine + both demo capability apps in one JVM over embedded Kafka
    // ---------------------------------------------------------------------------

    @BeforeAll
    static void bootEverything() throws Exception {
        kafka = new EmbeddedKafkaKraftBroker(1, 1,
                DEVICE_TOPIC, HR_TOPIC,
                "cap.device-financing.request.v1", "cap.device-financing.response.v1",
                "cap.fusion-hcm.request.v1", "cap.fusion-hcm.response.v1",
                "orig.decision.v1", "ops.journey.events.v1");
        kafka.afterPropertiesSet();
        brokers = kafka.getBrokersAsString();

        // The MOCKED-DATA BOUNDARY: a real HTTP server the capabilities call over
        // real HTTP. Docker-free in-JVM stub (the same pattern the platform's own
        // KarzaVehicleRcAdapterTest uses; WireMock is the compose-time equivalent).
        // The ONLY thing mocked in the whole flow is the DATA it returns.
        vendorStub = startVendorStub();
        vendorBase = "http://127.0.0.1:" + vendorStub.getAddress().getPort();

        // Everything as CLI args (highest precedence): full-flow-it's classpath
        // carries many modules' application.yml files, so nothing may depend on
        // WHICH yml Spring happens to pick.
        engine = new SpringApplicationBuilder(OriginationJourneyApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.autoconfigure.exclude=org.springframework.boot"
                                + ".autoconfigure.jdbc.DataSourceAutoConfiguration",
                        "--spring.kafka.bootstrap-servers=" + brokers,
                        "--spring.kafka.consumer.auto-offset-reset=earliest",
                        "--idfc.engine.journey-source=classpath",
                        "--idfc.engine.journey-resources[0]=journeys/device-financing.journey.json",
                        "--idfc.engine.journey-resources[1]=journeys/employee-lwd-update.journey.json",
                        "--idfc.engine.origination-topics=" + DEVICE_TOPIC + "," + HR_TOPIC,
                        "--idfc.engine.origination-group=legacy-demo-engine",
                        "--idfc.engine.response-group=legacy-demo-engine-responses",
                        "--idfc.engine.type-to-journey.DEVICE_FINANCING=device-financing",
                        "--idfc.engine.type-to-journey.EMPLOYEE_LWD_UPDATE=employee-lwd-update",
                        "--idfc.engine.state-store=in-memory",
                        "--OPS_API_TOKEN=" + OPS_TOKEN,
                        "--idfc.ops.auth-token=" + OPS_TOKEN);

        deviceCapability = bootDeviceCapability(false);
        // The fusion-hcm capability and the file-batch ingress edge are now two
        // separate deployables (capabilities:fusion-hcm + edges:file-batch-edge).
        fusionCapability = new SpringApplicationBuilder(FusionHcmApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.autoconfigure.exclude=org.springframework.boot"
                                + ".autoconfigure.jdbc.DataSourceAutoConfiguration",
                        "--spring.kafka.bootstrap-servers=" + brokers,
                        // real HTTP to the vendor stub — only the DATA is mocked
                        "--fusion.base-url=" + vendorBase + "/vendor/fusion");
        fileBatchEdge = new SpringApplicationBuilder(FileBatchEdgeApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.autoconfigure.exclude=org.springframework.boot"
                                + ".autoconfigure.jdbc.DataSourceAutoConfiguration",
                        "--spring.kafka.bootstrap-servers=" + brokers,
                        "--file-batch.enabled=true",
                        "--file-batch.inbox-dir=" + inbox.toAbsolutePath(),
                        "--file-batch.poll-ms=300",
                        "--file-batch.origination-topic=" + HR_TOPIC,
                        "--file-batch.type=EMPLOYEE_LWD_UPDATE",
                        "--file-batch.org-id=HR-DEMO");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer = new KafkaProducer<>(props);

        String port = engine.getEnvironment().getProperty("local.server.port");
        ops = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-Ops-Token", OPS_TOKEN)
                .defaultHeader("X-User-Id", "demo.it")
                .build();
    }

    /**
     * The brand table as CLI args — rows, not code. Each row carries only its
     * auth SCHEME + pass-logic path; the pass/decline/fail DATA comes back from
     * the real HTTP call to the vendor stub. HISENSE only when asked.
     */
    private static ConfigurableApplicationContext bootDeviceCapability(boolean withHisense) {
        java.util.List<String> args = new java.util.ArrayList<>(List.of(
                "--server.port=0",
                "--spring.autoconfigure.exclude=org.springframework.boot"
                        + ".autoconfigure.jdbc.DataSourceAutoConfiguration",
                "--spring.kafka.bootstrap-servers=" + brokers,
                // real HTTP to the vendor stub + OAuth token endpoint
                "--device-financing.vendor-base-url=" + vendorBase + "/vendor/device-financing",
                "--device-financing.token-url=" + vendorBase + "/oauth/token",
                "--device-financing.brands.SAMSUNG.auth-type=OAUTH",
                "--device-financing.brands.SAMSUNG.validation-required=true",
                "--device-financing.brands.SAMSUNG.pass-path=respCode",
                "--device-financing.brands.SAMSUNG.pass-value=0",
                "--device-financing.brands.SAMSUNG.scope=device-financing.samsung",
                "--device-financing.brands.GODREJ.auth-type=NA",
                "--device-financing.brands.GODREJ.validation-required=false",
                "--device-financing.brands.GODREJ.pass-path=status",
                "--device-financing.brands.GODREJ.pass-value=OK",
                "--device-financing.brands.BOSCH.auth-type=BAUTH",
                "--device-financing.brands.BOSCH.validation-required=true",
                "--device-financing.brands.BOSCH.pass-path=result.code",
                "--device-financing.brands.BOSCH.pass-value=S",
                "--device-financing.brands.BOSCH.basic-user=bosch-demo",
                "--device-financing.brands.BOSCH.basic-password=bosch-secret"));
        if (withHisense) {
            // THE "add a brand live" move: a config row (auth scheme + pass path).
            args.addAll(List.of(
                    "--device-financing.brands.HISENSE.auth-type=OAUTH",
                    "--device-financing.brands.HISENSE.validation-required=false",
                    "--device-financing.brands.HISENSE.pass-path=responseStatus",
                    "--device-financing.brands.HISENSE.pass-value=-4",
                    "--device-financing.brands.HISENSE.scope=device-financing.hisense"));
        }
        return new SpringApplicationBuilder(DeviceFinancingApplication.class)
                .run(args.toArray(String[]::new));
    }

    /**
     * The in-JVM vendor stub — the demo's mocked-DATA boundary, Docker-free.
     * Returns each brand's OWN response SHAPE (the per-brand pass-logic path is
     * real config), the decline/fail levers, real 401 when an auth-requiring
     * brand sends none, the Fusion 400 on a bad date, and an OAuth token.
     * This is the ONLY thing mocked; everything reaching it is real HTTP.
     */
    private static HttpServer startVendorStub() throws java.io.IOException {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/oauth/token", exchange -> respond(exchange, 200,
                Map.of("access_token", "demo-token", "token_type", "Bearer", "expires_in", 3600)));

        server.createContext("/vendor/device-financing/", exchange -> {
            Map<String, Object> body = readBody(exchange);
            String brand = String.valueOf(body.get("brand"));
            String deviceId = String.valueOf(body.get("deviceId"));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (java.util.Set.of("SAMSUNG", "BOSCH").contains(brand)
                    && (auth == null || auth.isBlank())) {
                respond(exchange, 401, Map.of("error", "missing_authorization"));
                return;
            }
            if ("DEV-FAIL".equals(deviceId)) {
                // 422: a permanent vendor rejection → PERMANENT (no retry storm,
                // deterministic red run). A 5xx would be TRANSIENT (retryable).
                respond(exchange, 422, Map.of("error", "device_rejected"));
                return;
            }
            boolean pass = !"DEV-DECLINE".equals(deviceId);
            respond(exchange, 200, deviceShape(brand, pass));
        });

        server.createContext("/vendor/fusion/employees/", exchange -> {
            Map<String, Object> body = readBody(exchange);
            String lwd = String.valueOf(body.get("lastWorkingDay"));
            if (!lwd.matches("\\d{4}-\\d{2}-\\d{2}")) {
                respond(exchange, 400, Map.of("error", "invalid_last_working_day"));
                return;
            }
            respond(exchange, 200, Map.of("status", "UPDATED", "lastWorkingDay", lwd));
        });

        server.start();
        return server;
    }

    private static Map<String, Object> deviceShape(String brand, boolean pass) {
        return switch (brand) {
            case "SAMSUNG" -> Map.of("respCode", pass ? "0" : "1");
            case "BOSCH" -> Map.of("result", Map.of("code", pass ? "S" : "F"));
            case "HISENSE" -> Map.of("responseStatus", pass ? "-4" : "-9");
            default -> Map.of("status", pass ? "OK" : "DECLINED");
        };
    }

    private static Map<String, Object> readBody(com.sun.net.httpserver.HttpExchange exchange)
            throws java.io.IOException {
        byte[] in = exchange.getRequestBody().readAllBytes();
        if (in.length == 0) {
            return Map.of();
        }
        return JSON.readValue(in, Map.class);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                Map<String, Object> body) throws java.io.IOException {
        byte[] out = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @AfterAll
    static void shutdown() {
        if (producer != null) producer.close();
        for (ConfigurableApplicationContext ctx :
                List.of(fileBatchEdge, fusionCapability, deviceCapability, engine)) {
            if (ctx != null && ctx.isActive()) ctx.close();
        }
        if (vendorStub != null) vendorStub.stop(0);
        if (kafka != null) kafka.destroy();
    }

    // ---------------------------------------------------------------------------
    // DEMO 1 — brand-as-config
    // ---------------------------------------------------------------------------

    @Test
    @Order(1)
    void brandAsConfig_oneJourney_perBrandPathsFromRows() throws Exception {
        send(DEVICE_TOPIC, deviceEnvelope("corr-samsung", "SAMSUNG", "DEV-1"));
        send(DEVICE_TOPIC, deviceEnvelope("corr-godrej", "GODREJ", "DEV-2"));
        send(DEVICE_TOPIC, deviceEnvelope("corr-bosch-decline", "BOSCH", "DEV-DECLINE"));
        send(DEVICE_TOPIC, deviceEnvelope("corr-samsung-fail", "SAMSUNG", "DEV-FAIL"));

        JsonNode samsung = awaitTerminalRun("corr-samsung");
        JsonNode godrej = awaitTerminalRun("corr-godrej");
        JsonNode declined = awaitTerminalRun("corr-bosch-decline");
        JsonNode failed = awaitTerminalRun("corr-samsung-fail");

        assertThat(samsung.get("status").asText()).isEqualTo("COMPLETED_APPROVED");
        assertThat(nodeIdsOf(samsung))
                .as("SAMSUNG's row says validation-required -> the validate hop RUNS")
                .contains("n_validate", "n_block");

        assertThat(godrej.get("status").asText()).isEqualTo("COMPLETED_APPROVED");
        assertThat(nodeIdsOf(godrej))
                .as("GODREJ's row says block-only -> NO validate hop, same journey")
                .contains("n_block")
                .doesNotContain("n_validate");

        assertThat(declined.get("status").asText())
                .as("vendor says no = a business DECLINE (completion), never red")
                .isEqualTo("COMPLETED_DECLINED");

        assertThat(failed.get("status").asText()).startsWith("FAILED");
        assertThat(failureClassesOf(failed))
                .as("the vendor failure carries its CLASS (enum name only)")
                .contains("PERMANENT");
    }

    @Test
    @Order(2)
    void unknownBrand_failsClosed_thenBecomesABrandViaConfigRowsAlone() throws Exception {
        // Before the row exists: HISENSE is REFUSED (fail closed) — the legacy
        // estate's fail-open unknown-orgId is deliberately not reproduced.
        send(DEVICE_TOPIC, deviceEnvelope("corr-hisense-1", "HISENSE", "DEV-9"));
        JsonNode refused = awaitTerminalRun("corr-hisense-1");
        assertThat(refused.get("status").asText()).startsWith("FAILED");

        // The "live add": restart the capability with TWO more config rows —
        // same jar, no rebuild — and HISENSE is a first-class brand.
        deviceCapability.close();
        deviceCapability = bootDeviceCapability(true);

        send(DEVICE_TOPIC, deviceEnvelope("corr-hisense-2", "HISENSE", "DEV-9"));
        JsonNode approved = awaitTerminalRun("corr-hisense-2");
        assertThat(approved.get("status").asText()).isEqualTo("COMPLETED_APPROVED");
        assertThat(nodeIdsOf(approved))
                .as("HISENSE row: block-only, passes on responseStatus == -4")
                .contains("n_block")
                .doesNotContain("n_validate");
    }

    // ---------------------------------------------------------------------------
    // DEMO 2 — file-batch scaffold
    // ---------------------------------------------------------------------------

    private static final String SAMPLE_CSV = """
            employeeId,lastWorkingDay
            EMP-001,2026-07-31
            EMP-002,2026-08-15
            EMP-003,2026-07-20
            EMP-004,not-a-date
            EMP-005,2026-09-01
            """;

    @Test
    @Order(3)
    void fileBatch_oneRunPerRecord_perRecordStatus_inTheOpsWindow() throws Exception {
        Files.writeString(inbox.resolve("employees-sample.csv"), SAMPLE_CSV);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(terminalLwdRuns()).hasSize(5));

        List<JsonNode> runs = terminalLwdRuns();
        String batchId = runs.get(0).get("notificationId").asText();
        assertThat(batchId).startsWith("batch-");
        assertThat(runs)
                .as("the WHOLE batch rides one notificationId — one ops search key")
                .allSatisfy(r -> assertThat(r.get("notificationId").asText()).isEqualTo(batchId));

        List<String> statuses = runs.stream().map(r -> r.get("status").asText()).toList();
        assertThat(statuses.stream().filter("COMPLETED_APPROVED"::equals))
                .as("batch of 5: 4 succeeded").hasSize(4);
        assertThat(statuses.stream().filter(s -> s.startsWith("FAILED")))
                .as("…and the crafted bad record failed — alone").hasSize(1);

        // The failed record is EMP-004 with a CLASS, visible in the run detail.
        JsonNode failedRun = runs.stream()
                .filter(r -> r.get("status").asText().startsWith("FAILED"))
                .findFirst().orElseThrow();
        assertThat(failedRun.get("sfdcRecordId").asText()).isEqualTo("EMP-004");
        JsonNode detail = opsDetail(failedRun.get("runId").asText());
        assertThat(failureClassesOf(detail)).contains("PERMANENT");

        // The batch-as-search-key claim, verified through /ops/runs/search.
        JsonNode found = ops.get().uri("/ops/runs/search?key=" + batchId)
                .retrieve().body(JsonNode.class);
        assertThat(found).hasSize(5);
    }

    @Test
    @Order(4)
    void reDropIsRefusedTwiceOver_ledgerThenEngineDedup() throws Exception {
        // Layer 1 — the ledger: same CONTENT under a new name is skipped cold.
        Path again = inbox.resolve("employees-again.csv");
        Files.writeString(again, SAMPLE_CSV);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> !Files.exists(again)); // poller archived it
        assertThat(terminalLwdRuns()).hasSize(5);

        // Layer 2 — the ENGINE: wipe the ledger, re-drop; the poller re-publishes
        // but the deterministic notificationIds hit insertIfAbsent — still 5 runs.
        Files.deleteIfExists(inbox.resolve(".processed.ledger"));
        Path third = inbox.resolve("employees-third.csv");
        Files.writeString(third, SAMPLE_CSV);
        await().atMost(Duration.ofSeconds(30)).until(() -> !Files.exists(third));
        // Give any (wrong) duplicate runs time to appear before asserting.
        Thread.sleep(3000);
        assertThat(terminalLwdRuns())
                .as("engine dedup: deterministic per-record ids => no double runs "
                        + "even with the ledger gone (the legacy job re-runs blind)")
                .hasSize(5);
    }

    @Test
    @Order(5)
    void emptyFile_isSkipped_notDispatched() throws Exception {
        Path empty = inbox.resolve("employees-empty.csv");
        Files.writeString(empty, "employeeId,lastWorkingDay\n");
        await().atMost(Duration.ofSeconds(30)).until(() -> !Files.exists(empty));
        assertThat(terminalLwdRuns()).hasSize(5);
    }

    // ---------------------------------------------------------------------------
    // plumbing
    // ---------------------------------------------------------------------------

    private static void send(String topic, Map<String, Object> envelope) throws Exception {
        producer.send(new ProducerRecord<>(topic,
                String.valueOf(envelope.get("notificationId")),
                JSON.writeValueAsString(envelope))).get();
    }

    private static Map<String, Object> deviceEnvelope(String correlationId,
                                                      String brand, String deviceId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("transactionId", correlationId + "-t");
        envelope.put("schemaVersion", "demo.v1");
        envelope.put("source", "FILE_DEMO");
        envelope.put("type", "DEVICE_FINANCING");
        envelope.put("notificationId", correlationId + "-n");
        envelope.put("orgId", "DEMO-ORG");
        envelope.put("sfdcRecordId", deviceId);
        envelope.put("applicationRef", correlationId + "-app");
        envelope.put("correlationId", correlationId);
        envelope.put("originalCorrelationId", correlationId);
        envelope.put("payloadContentType", "application/json");
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", Map.of("brand", brand, "deviceId", deviceId));
        return envelope;
    }

    /** Search /ops by correlationId until the run exists AND is terminal; return its DETAIL. */
    private static JsonNode awaitTerminalRun(String correlationId) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            JsonNode found = ops.get().uri("/ops/runs/search?key=" + correlationId)
                    .retrieve().body(JsonNode.class);
            assertThat(found).isNotNull();
            assertThat(found.size()).isEqualTo(1);
            assertThat(found.get(0).get("status").asText()).isNotEqualTo("RUNNING");
        });
        JsonNode summary = ops.get().uri("/ops/runs/search?key=" + correlationId)
                .retrieve().body(JsonNode.class).get(0);
        return opsDetail(summary.get("runId").asText());
    }

    private static JsonNode opsDetail(String runId) {
        return ops.get().uri("/ops/runs/" + runId).retrieve().body(JsonNode.class);
    }

    /** All TERMINAL employee-lwd-update run summaries from the ops list window. */
    private static List<JsonNode> terminalLwdRuns() {
        JsonNode page = ops.get()
                .uri("/ops/runs?journeyKey=employee-lwd-update&size=200")
                .retrieve().body(JsonNode.class);
        return StreamSupport.stream(page.get("items").spliterator(), false)
                .filter(r -> !"RUNNING".equals(r.get("status").asText()))
                .toList();
    }

    private static List<String> nodeIdsOf(JsonNode detail) {
        return StreamSupport.stream(detail.get("transitions").spliterator(), false)
                .map(t -> t.get("nodeId").asText())
                .toList();
    }

    private static List<String> failureClassesOf(JsonNode detail) {
        return StreamSupport.stream(detail.get("nodeStats").spliterator(), false)
                .map(s -> s.path("failureClass").asText(""))
                .toList();
    }
}
