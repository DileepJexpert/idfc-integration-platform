package com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza;

import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.AuthType;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard for Phase 1 item 6: a Karza endpoint that stalls past the read
 * timeout must fail FAST and be classified AMBIGUOUS (not block the consumer thread
 * forever). The @Timeout proves the call returns rather than hanging.
 */
class KarzaClientTimeoutTest {

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/karza/slow", exchange -> {
            try {
                Thread.sleep(2_000); // stall well past the client's read timeout
                byte[] out = "{}".getBytes();
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
            } catch (Exception ignored) {
                // client already timed out and closed the connection
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ResolvedRoute route() {
        int port = server.getAddress().getPort();
        return new ResolvedRoute("KARZA_SLOW", "http://127.0.0.1:" + port + "/karza/slow", AuthType.NONE);
    }

    @Test
    @Timeout(5) // fails if the client hangs instead of honouring the read timeout
    void readTimeoutIsClassifiedAmbiguous() {
        KarzaClient client = new KarzaClient(svc -> "tok-" + svc, 1_000, 200);

        assertThatThrownBy(() -> client.post(route(), Map.of("reg_no", "AB12CD1234")))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> {
                    VerificationException ve = (VerificationException) e;
                    assertThat(ve.errorClass()).isEqualTo(ErrorClass.AMBIGUOUS);
                    assertThat(ve.errorCode()).isEqualTo("READ_TIMEOUT");
                });
    }
}
