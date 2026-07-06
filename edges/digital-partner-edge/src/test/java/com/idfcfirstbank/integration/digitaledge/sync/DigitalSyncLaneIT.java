package com.idfcfirstbank.integration.digitaledge.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.digitaledge.adapter.in.rest.sync.ConfiguredBearerTokenValidator;
import com.idfcfirstbank.integration.digitaledge.adapter.in.rest.sync.ImpsFtController;
import com.idfcfirstbank.integration.digitaledge.adapter.in.rest.sync.LmsUtilitiesController;
import com.idfcfirstbank.integration.digitaledge.adapter.in.rest.sync.SyncConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * END-TO-END (Docker-free) proof of the digital-lending SYNC lane AS HOSTED ON THE
 * EDGE: the real sync assembly (Bearer validation → SyncCapabilityInvoker →
 * in-thread capability → real HTTP client) against in-JVM vendor stubs. Boots ONLY
 * the sync lane (no Kafka/Aerospike — the async edge is untouched) and drives both
 * real doors: {@code POST /api/v1/impsFT} (INDMONEY) and {@code POST
 * /api/v1/callLmsUtilities} (SAVEIN). Proves the whole synchronous contract on one
 * blocking call each: happy path, business-"no" (not a 5xx), technical error
 * (uniform 5xx), idempotency, house-envelope mapping, no-offer, unknown-requestCode
 * fail-closed, and fail-closed auth.
 */
@SpringBootTest(
        classes = DigitalSyncLaneIT.SyncLaneTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "idfc.sync-edge.auth.accepted-tokens=test-token",
                "imps-disbursal.read-timeout-ms=700",   // short, so the timeout case is quick
                "lms-utilities.known-request-codes=OFFER_CHECK"
        })
class DigitalSyncLaneIT {

    /** The sync lane ONLY — no Kafka, no Aerospike, no async edge beans. */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
    @Import({SyncConfiguration.class, ImpsFtController.class,
            LmsUtilitiesController.class, ConfiguredBearerTokenValidator.class})
    static class SyncLaneTestApp {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static HttpServer vendor;
    private static final Map<String, AtomicInteger> impsCallsByIdempotentId = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void vendorUrls(DynamicPropertyRegistry registry) throws IOException {
        startVendor();
        String base = "http://127.0.0.1:" + vendor.getAddress().getPort();
        registry.add("imps-disbursal.vendor-base-url", () -> base);
        registry.add("lms-utilities.vendor-base-url", () -> base);
    }

    // ---- the in-JVM vendor stubs (only the response DATA is mocked) ------------

    private static void startVendor() throws IOException {
        vendor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vendor.setExecutor(Executors.newFixedThreadPool(4));
        vendor.createContext("/api/v1/impsFT", DigitalSyncLaneIT::handleImps);
        vendor.createContext("/api/v1/callLmsUtilities", DigitalSyncLaneIT::handleLms);
        vendor.start();
    }

    private static void handleImps(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJson(exchange);
        String acc = String.valueOf(body.get("custBankAccNo"));
        impsCallsByIdempotentId.computeIfAbsent(String.valueOf(body.get("idempotentId")), k -> new AtomicInteger())
                .incrementAndGet();
        switch (acc) {
            case "SERVER-ERROR" -> respond(exchange, 500, Map.of("error", "backend_unavailable"));
            case "SLOW-ACC" -> {
                sleep(1500);   // exceeds the client read-timeout (700ms) -> AMBIGUOUS
                respond(exchange, 200, impsSuccess(body));
            }
            case "BAD-ACCOUNT" -> respond(exchange, 200, impsBusinessDecline(body));
            default -> respond(exchange, 200, impsSuccess(body));
        }
    }

    private static void handleLms(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJson(exchange);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", "SUCCESS");
        metadata.put("message", "OFFER_CHECK processed successfully");
        metadata.put("version", "v1");
        metadata.put("time", "2026-07-06T10:21:06");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metadata", metadata);
        if ("NO-OFFER-CRN".equals(String.valueOf(body.get("crnNo")))) {
            out.put("resource_data", List.of());   // a legitimate business "no offer"
        } else {
            out.put("resource_data", List.of(Map.of(
                    "EXPIRED_DATE", "2030-10-03T06:57:03", "LOAN_AMOUNT", "500000",
                    "REQID", String.valueOf(body.get("crnNo")), "RISK_SEGMENT", "LOW RISK", "ROI", "14")));
        }
        respond(exchange, 200, out);
    }

    private static Map<String, Object> impsSuccess(Map<String, Object> req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reqId", req.get("reqId"));
        m.put("status", "S");
        m.put("transactionId", "003712585052");
        m.put("custBankAccNo", req.get("custBankAccNo"));
        m.put("customerName", "Bene AC Holder ");
        m.put("errCode", "");
        m.put("errMessage", "");
        return m;
    }

    private static Map<String, Object> impsBusinessDecline(Map<String, Object> req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reqId", req.get("reqId"));
        m.put("status", "F");
        m.put("transactionId", "");
        m.put("custBankAccNo", req.get("custBankAccNo"));
        m.put("customerName", "");
        m.put("errCode", "E01");
        m.put("errMessage", "invalid beneficiary account");
        return m;
    }

    @AfterAll
    static void stop() {
        if (vendor != null) {
            vendor.stop(0);
        }
    }

    // ---- IMPS (money movement) -------------------------------------------------

    @Test
    void impsSyncHappyPath_returnsMappedResult() throws Exception {
        Response r = post("/api/v1/impsFT", "test-token", impsBody("IDEM-OK-1", "2026040915306622"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status")).isEqualTo("S");
        assertThat(r.body().get("transactionId")).isEqualTo("003712585052");
    }

    @Test
    void impsBusinessNo_is200NotA5xx() throws Exception {
        Response r = post("/api/v1/impsFT", "test-token", impsBody("IDEM-BIZ-1", "BAD-ACCOUNT"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status")).isEqualTo("F");
        assertThat(r.body().get("errCode")).isEqualTo("E01");
    }

    @Test
    void impsReadTimeout_isUniform502Ambiguous_noFakeSuccess() throws Exception {
        Response r = post("/api/v1/impsFT", "test-token", impsBody("IDEM-TMO-1", "SLOW-ACC"));
        assertThat(r.status()).isEqualTo(502);
        assertThat(r.body().get("errorClass")).isEqualTo("AMBIGUOUS");
        assertThat(r.body()).doesNotContainKey("transactionId");
    }

    @Test
    void impsSameIdempotentId_singleTransfer() throws Exception {
        Response first = post("/api/v1/impsFT", "test-token", impsBody("IDEM-DUP-1", "2026040915306622"));
        Response second = post("/api/v1/impsFT", "test-token", impsBody("IDEM-DUP-1", "2026040915306622"));
        assertThat(first.status()).isEqualTo(200);
        assertThat(second.body().get("transactionId")).isEqualTo(first.body().get("transactionId"));
        assertThat(impsCallsByIdempotentId.get("IDEM-DUP-1").get())
                .as("the backend was hit ONCE despite two requests").isEqualTo(1);
    }

    @Test
    void impsMissingBearer_is401() throws Exception {
        Response r = post("/api/v1/impsFT", null, impsBody("IDEM-NOAUTH-1", "2026040915306622"));
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void impsMissingIdempotentId_is400() throws Exception {
        Map<String, Object> noKey = impsBody("x", "2026040915306622");
        noKey.remove("idempotentId");
        Response r = post("/api/v1/impsFT", "test-token", noKey);
        assertThat(r.status()).isEqualTo(400);
    }

    // ---- LMS utilities (house envelope) ----------------------------------------

    @Test
    void lmsOfferCheck_returnsMappedResourceData() throws Exception {
        Response r = post("/api/v1/callLmsUtilities", "test-token", lmsBody("OFFER_CHECK", "pbline_5eb16741"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status")).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.body().get("resourceData");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("LOAN_AMOUNT")).isEqualTo("500000");
        assertThat(rows.get(0).get("ROI")).isEqualTo("14");
    }

    @Test
    void lmsNoOffer_isCleanEmptyResult_notAnError() throws Exception {
        Response r = post("/api/v1/callLmsUtilities", "test-token", lmsBody("OFFER_CHECK", "NO-OFFER-CRN"));
        assertThat(r.status()).as("a no-offer is a SUCCESS with an empty result, NOT an error").isEqualTo(200);
        assertThat(r.body().get("status")).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.body().get("resourceData");
        assertThat(rows).isEmpty();
    }

    @Test
    void lmsUnknownRequestCode_failsClosed_422() throws Exception {
        Response r = post("/api/v1/callLmsUtilities", "test-token", lmsBody("BALANCE_CHECK", "pbline_5eb16741"));
        assertThat(r.status()).as("an unsupported requestCode fails closed (422), never runs").isEqualTo(422);
        assertThat(r.body().get("code")).isEqualTo("UNKNOWN_REQUEST_CODE");
    }

    @Test
    void lmsMissingBearer_is401() throws Exception {
        Response r = post("/api/v1/callLmsUtilities", null, lmsBody("OFFER_CHECK", "pbline_5eb16741"));
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void lmsMissingRequestCode_is400() throws Exception {
        Map<String, Object> noCode = lmsBody("OFFER_CHECK", "pbline_5eb16741");
        noCode.remove("requestCode");
        Response r = post("/api/v1/callLmsUtilities", "test-token", noCode);
        assertThat(r.status()).isEqualTo(400);
    }

    // ---- plumbing --------------------------------------------------------------

    private static Map<String, Object> impsBody(String idempotentId, String custBankAccNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("custBankAccNo", custBankAccNo);
        m.put("idempotentId", idempotentId);
        m.put("ifscCode", "UTIB0000001");
        m.put("reqId", "INDMONEY202601121110A101");
        m.put("source", "INDMONEY");
        m.put("loanNo", "110855952");
        m.put("isDisbursalFlag", "Y");
        return m;
    }

    private static Map<String, Object> lmsBody(String requestCode, String crnNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityName", "PBLINE");
        m.put("agreementId", "pbline_5eb16741c06cf6dd368e0cea7f41f838");
        m.put("crnNo", crnNo);
        m.put("requestCode", requestCode);
        return m;
    }

    private Response post(String path, String bearer, Map<String, Object> body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("correlationId", "corr-1")
                .header("transactionId", "txn-1")
                .header("source", path.contains("imps") ? "INDMONEY" : "SAVEIN")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = resp.body() == null || resp.body().isBlank()
                ? Map.of() : JSON.readValue(resp.body(), Map.class);
        return new Response(resp.statusCode(), parsed);
    }

    private record Response(int status, Map<String, Object> body) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] in = exchange.getRequestBody().readAllBytes();
        return in.length == 0 ? Map.of() : JSON.readValue(in, Map.class);
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body) {
        try {
            byte[] out = JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        } catch (IOException ignored) {
            // the client may have already timed out (SLOW-ACC) — writing is a no-op
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
