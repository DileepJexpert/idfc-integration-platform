package com.idfcfirstbank.integration.capabilities.sfdcusermgmt;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.SfdcOrgRouteResolver;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.SfdcUserManagementService;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.mapper.SfdcMapperRegistry;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.adapter.out.http.SfdcOrgHttpClient;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.adapter.out.idempotency.InMemorySfdcIdempotencyStore;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.config.SfdcUserManagementProperties;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The REAL-HTTP org-routing proof: two independent HTTP servers (org A and org B) on
 * distinct ports, wired through the actual {@link SfdcOrgHttpClient}. A request for ORG_A
 * demonstrably reaches server A only, ORG_B reaches server B only — proving the org name,
 * not the svcName, selects the egress target. (The dockerised mock-sfdc-org-a/-b WireMocks
 * are the same proof at the compose level; this keeps it deterministic and docker-free.)
 */
class SfdcOrgRoutingIT {

    private static final String PATH = "/services/apexrest/usermgmt/user/fetch";
    private static final SyncRequestContext CTX = SyncRequestContext.of("corr-1", "txn-1", "JMI");

    private HttpServer serverA;
    private HttpServer serverB;
    private final AtomicInteger hitsA = new AtomicInteger();
    private final AtomicInteger hitsB = new AtomicInteger();
    private SfdcUserManagementService service;

    @BeforeEach
    void setUp() throws IOException {
        serverA = start(hitsA, "ORG_A");
        serverB = start(hitsB, "ORG_B");
        SfdcUserManagementProperties props = new SfdcUserManagementProperties(2000, 3000,
                List.of(new SfdcUserManagementProperties.Route("SFDC_USER_FETCH", PATH, false)),
                List.of(new SfdcUserManagementProperties.Org("ORG_A", baseUrl(serverA), "NONE", null, true),
                        new SfdcUserManagementProperties.Org("ORG_B", baseUrl(serverB), "NONE", null, true)));
        service = new SfdcUserManagementService(
                new SfdcOrgRouteResolver(props), new SfdcMapperRegistry(), new SfdcOrgHttpClient(props),
                new com.idfcfirstbank.integration.capabilities.sfdcusermgmt.adapter.out.idempotency.InMemorySfdcIdempotencyStore());
    }

    @AfterEach
    void tearDown() {
        serverA.stop(0);
        serverB.stop(0);
    }

    @Test
    void orgAHitsServerAAndOrgBHitsServerB() {
        Map<String, Object> respA = service.invoke("SFDC_USER_FETCH", body("ORG_A"), CTX);
        assertThat(respA).containsEntry("org", "ORG_A");
        assertThat(hitsA.get()).isEqualTo(1);
        assertThat(hitsB.get()).isZero();

        Map<String, Object> respB = service.invoke("SFDC_USER_FETCH", body("ORG_B"), CTX);
        assertThat(respB).containsEntry("org", "ORG_B");
        assertThat(hitsB.get()).isEqualTo(1);
        assertThat(hitsA.get()).isEqualTo(1);   // server A was NOT hit again — routing is by org
    }

    @Test
    void writeWithAnEmpty2xxIsAmbiguousNotPermanent() throws IOException {
        // A successful-but-empty-bodied create may have applied -> the caller must not be
        // told "definitely didn't happen" (PERMANENT), which could invite a double-create.
        HttpServer empty = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        empty.createContext("/svc/create", ex -> { ex.sendResponseHeaders(200, -1); ex.close(); });
        empty.start();
        try {
            SfdcUserManagementProperties props = new SfdcUserManagementProperties(2000, 3000,
                    List.of(new SfdcUserManagementProperties.Route("SFDC_USER_CREATE", "/svc/create", true)),
                    List.of(new SfdcUserManagementProperties.Org("ORG_A",
                            "http://localhost:" + empty.getAddress().getPort(), "NONE", null, true)));
            SfdcUserManagementService svc = new SfdcUserManagementService(
                    new SfdcOrgRouteResolver(props), new SfdcMapperRegistry(),
                    new SfdcOrgHttpClient(props), new InMemorySfdcIdempotencyStore());
            Map<String, Object> b = Map.of("svcName", "SFDC_USER_CREATE", "orgName", "ORG_A",
                    "idempotencyKey", "k-empty", "payload", Map.of("Username", "u@a"));
            assertThatThrownBy(() -> svc.invoke("SFDC_USER_CREATE", b, CTX))
                    .isInstanceOfSatisfying(SyncTechnicalException.class, e -> {
                        assertThat(e.code()).isEqualTo("EMPTY_RESPONSE");
                        assertThat(e.errorClass()).isEqualTo(ErrorClass.AMBIGUOUS);
                    });
        } finally {
            empty.stop(0);
        }
    }

    private static Map<String, Object> body(String org) {
        return Map.of("svcName", "SFDC_USER_FETCH", "orgName", org, "payload", Map.of("crn", "C1"));
    }

    private static String baseUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static HttpServer start(AtomicInteger counter, String org) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, exchange -> {
            counter.incrementAndGet();
            byte[] out = ("{\"org\":\"" + org + "\",\"totalSize\":1}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return server;
    }
}
