package com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza;

import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.AuthType;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Karza adapter over a real HTTP round-trip (in-JVM stub, Docker-free): sends the
 * OAuth Bearer header, returns the raw body on 200, and CLASSIFIES failures for retry/DLQ
 * (4xx = PERMANENT, 5xx = TRANSIENT). WireMock is the compose-time equivalent.
 */
class KarzaVehicleRcAdapterTest {

    private HttpServer server;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{\"metadata\":{\"status\":\"ACTIVE\"},\"resource_data\":[{\"rcStatus\":\"ACTIVE\"}]}";

    private final KarzaVehicleRcAdapter adapter = new KarzaVehicleRcAdapter(svc -> "tok-" + svc);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/karza/vahan-rc", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ResolvedRoute route() {
        int port = server.getAddress().getPort();
        return new ResolvedRoute("KARZA_VAHAN_RC", "http://127.0.0.1:" + port + "/karza/vahan-rc", AuthType.OAUTH_BEARER);
    }

    @Test
    void success200ReturnsRawBodyAndSendsBearerToken() {
        Map<String, Object> raw = adapter.call(route(), Map.of("reg_no", "AB12CD1234", "consent", "Y"));

        assertThat(raw).containsKey("resource_data");
        assertThat(lastAuth.get()).isEqualTo("Bearer tok-KARZA_VAHAN_RC");   // OAuth header sent
    }

    @Test
    void http4xxIsPermanent() {
        status = 400;
        assertThatThrownBy(() -> adapter.call(route(), Map.of("reg_no", "AB12CD1234")))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> assertThat(((VerificationException) e).errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }

    @Test
    void http5xxIsTransient() {
        status = 503;
        assertThatThrownBy(() -> adapter.call(route(), Map.of("reg_no", "AB12CD1234")))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> assertThat(((VerificationException) e).errorClass()).isEqualTo(ErrorClass.TRANSIENT));
    }
}
