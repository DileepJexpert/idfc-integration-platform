package com.idfcfirstbank.integration.capabilities.impsdisbursal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * END-TO-END (Docker-free) proof of the digital-lending SYNC lane for imps-disbursal:
 * the REAL Spring app (controller → invoker → service → real HTTP client) against an
 * in-JVM vendor stub (the mock-imps equivalent; only the DATA is mocked). Proves the
 * whole synchronous contract on one blocking call — NO Kafka, NO engine:
 * happy path, business-"no" (not a 5xx), technical error (uniform 5xx, no fake
 * success), idempotency (single transfer on repeat), and fail-closed auth.
 */
@SpringBootTest(
        classes = ImpsDisbursalApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "imps-disbursal.auth.accepted-tokens=test-token",
                "imps-disbursal.read-timeout-ms=700"   // short, so the timeout case is quick
        })
class ImpsDisbursalSyncIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static HttpServer vendor;
    private static final Map<String, AtomicInteger> callsByIdempotentId = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void vendorUrl(DynamicPropertyRegistry registry) throws IOException {
        startVendor();
        registry.add("imps-disbursal.vendor-base-url",
                () -> "http://127.0.0.1:" + vendor.getAddress().getPort());
    }

    // ---- the in-JVM IMPS backend stub (only the response DATA is mocked) --------

    private static void startVendor() throws IOException {
        vendor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vendor.setExecutor(Executors.newFixedThreadPool(4));
        vendor.createContext("/api/v1/impsFT", ImpsDisbursalSyncIT::handle);
        vendor.start();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJson(exchange);
        String acc = String.valueOf(body.get("custBankAccNo"));
        String idem = String.valueOf(body.get("idempotentId"));
        callsByIdempotentId.computeIfAbsent(idem, k -> new AtomicInteger()).incrementAndGet();

        switch (acc) {
            case "SERVER-ERROR" -> respond(exchange, 500, Map.of("error", "backend_unavailable"));
            case "SLOW-ACC" -> {
                sleep(1500);   // exceeds the client read-timeout (700ms) -> read timeout -> AMBIGUOUS
                respond(exchange, 200, success(body));   // (client has already given up; write may no-op)
            }
            case "BAD-ACCOUNT" -> respond(exchange, 200, businessDecline(body));
            default -> respond(exchange, 200, success(body));
        }
    }

    private static Map<String, Object> success(Map<String, Object> req) {
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

    private static Map<String, Object> businessDecline(Map<String, Object> req) {
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

    // ---- the tests -------------------------------------------------------------

    @Test
    void syncHappyPath_returnsTheMappedResultOnTheSameCall() throws Exception {
        Response r = post("test-token", body("IDEM-OK-1", "2026040915306622"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status")).isEqualTo("S");
        assertThat(r.body().get("transactionId")).isEqualTo("003712585052");
        assertThat(r.body().get("customerName")).isEqualTo("Bene AC Holder ");
    }

    @Test
    void businessNo_isReturnedAsAResult_notA5xx() throws Exception {
        Response r = post("test-token", body("IDEM-BIZ-1", "BAD-ACCOUNT"));

        assertThat(r.status()).as("a business decline is a 200 result, NOT a technical 5xx").isEqualTo(200);
        assertThat(r.body().get("status")).isEqualTo("F");
        assertThat(r.body().get("errCode")).isEqualTo("E01");
    }

    @Test
    void technicalReadTimeout_isUniform5xx_ambiguous_noFakeSuccess() throws Exception {
        Response r = post("test-token", body("IDEM-TMO-1", "SLOW-ACC"));

        assertThat(r.status()).as("a hung downstream fails fast with a uniform 5xx").isEqualTo(502);
        assertThat(r.body().get("status")).isEqualTo("ERROR");
        assertThat(r.body().get("errorClass"))
                .as("a read timeout on a money movement is AMBIGUOUS, never a fake success")
                .isEqualTo("AMBIGUOUS");
        assertThat(r.body()).doesNotContainKey("transactionId");
    }

    @Test
    void technicalServerError_isUniform5xx_transient() throws Exception {
        Response r = post("test-token", body("IDEM-5XX-1", "SERVER-ERROR"));

        assertThat(r.status()).isEqualTo(502);
        assertThat(r.body().get("errorClass")).isEqualTo("TRANSIENT");
    }

    @Test
    void sameIdempotentId_twice_singleTransfer_priorResultReturned() throws Exception {
        Response first = post("test-token", body("IDEM-DUP-1", "2026040915306622"));
        Response second = post("test-token", body("IDEM-DUP-1", "2026040915306622"));

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("transactionId")).isEqualTo(first.body().get("transactionId"));
        assertThat(callsByIdempotentId.get("IDEM-DUP-1").get())
                .as("the backend was hit ONCE despite two requests — no double transfer")
                .isEqualTo(1);
    }

    @Test
    void missingBearer_isRejectedFailClosed() throws Exception {
        Response r = post(null, body("IDEM-NOAUTH-1", "2026040915306622"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(callsByIdempotentId.containsKey("IDEM-NOAUTH-1"))
                .as("auth fails before the backend is ever called").isFalse();
    }

    @Test
    void wrongBearer_isRejectedFailClosed() throws Exception {
        Response r = post("not-the-token", body("IDEM-BADAUTH-1", "2026040915306622"));
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void missingIdempotentId_isRejected_beforeAnyTransfer() throws Exception {
        Map<String, Object> noKey = body("placeholder", "2026040915306622");
        noKey.remove("idempotentId");
        Response r = post("test-token", noKey);
        assertThat(r.status()).isEqualTo(400);
    }

    // ---- plumbing --------------------------------------------------------------

    private static Map<String, Object> body(String idempotentId, String custBankAccNo) {
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

    private Response post(String bearer, Map<String, Object> body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/impsFT"))
                .header("Content-Type", "application/json")
                .header("correlationId", "corr-" + body.get("idempotentId"))
                .header("transactionId", "txn-" + body.get("idempotentId"))
                .header("source", "INDMONEY")
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
            // The client may have already timed out (the SLOW-ACC case) — writing is a no-op.
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
