package com.idfcfirstbank.integration.capabilities.verification.application;

import com.idfcfirstbank.integration.capabilities.verification.adapter.out.echo.EchoVerificationAdapter;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.route.ConfigRouteResolver;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties.Retry;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties.Route;
import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationAdapter;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationDlqPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The correction #2 regression: the old wrapper silently ack'd failures and LOST them.
 * Here a failing downstream is retried (TRANSIENT) or failed fast (PERMANENT) and then
 * DEAD-LETTERED — never silently lost — and the engine gets a classified ERROR.
 */
class VerificationDlqTest {

    private final List<String> dlq = new ArrayList<>();
    private final VerificationDlqPort recordingDlq = (req, reason) -> dlq.add(req.operation() + ":" + reason);

    private final AtomicInteger transientCalls = new AtomicInteger();
    private final AtomicInteger permanentCalls = new AtomicInteger();

    private final VerificationAdapter transientFail = adapter("FAIL_T", () -> {
        transientCalls.incrementAndGet();
        throw new VerificationException(ErrorClass.TRANSIENT, "DOWNSTREAM_5XX", "karza 503");
    });
    private final VerificationAdapter permanentFail = adapter("FAIL_P", () -> {
        permanentCalls.incrementAndGet();
        throw new VerificationException(ErrorClass.PERMANENT, "BAD_REQUEST", "karza 400");
    });

    private final VerificationProperties props = new VerificationProperties(
            List.of(new Route("ECHO", "http://echo.mock/echo", "NONE"),
                    new Route("FAIL_T", "http://karza.mock/t", "OAUTH_BEARER"),
                    new Route("FAIL_P", "http://karza.mock/p", "OAUTH_BEARER")),
            List.of("echo.mock", "karza.mock"),
            new Retry(2, 0),   // 2 attempts, no backoff (fast test)
            "cap.verification.dlq.v1");

    private final VerificationService service = new VerificationService(
            new ConfigRouteResolver(props),
            new AdapterRegistry(List.of(new EchoVerificationAdapter(), transientFail, permanentFail)),
            new MapperRegistry());

    private final VerificationDispatcher dispatcher = new VerificationDispatcher(service, recordingDlq, 2, 0);

    @Test
    void aPermanentFailureIsDeadLetteredImmediatelyNotRetriedNotLost() {
        CapabilityResponse resp = dispatcher.handle(request("FAIL_P"));

        assertThat(permanentCalls.get()).as("permanent = no retry").isEqualTo(1);
        assertThat(dlq).as("parked in DLQ, not silent-ack'd").hasSize(1);
        assertThat(dlq.get(0)).contains("FAIL_P").contains("BAD_REQUEST");
        assertThat(resp.status()).isEqualTo(CapabilityStatus.ERROR);
        assertThat(resp.errorClass()).isEqualTo(ErrorClass.PERMANENT);
    }

    @Test
    void aTransientFailureIsRetriedThenDeadLetteredNeverLost() {
        CapabilityResponse resp = dispatcher.handle(request("FAIL_T"));

        assertThat(transientCalls.get()).as("retried up to maxAttempts").isEqualTo(2);
        assertThat(dlq).as("still ends in DLQ, never silently lost").hasSize(1);
        assertThat(resp.status()).isEqualTo(CapabilityStatus.ERROR);
        assertThat(resp.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
    }

    @Test
    void aSuccessNeverTouchesTheDlq() {
        CapabilityResponse resp = dispatcher.handle(request("ECHO"));

        assertThat(dlq).isEmpty();
        assertThat(resp.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(resp.result()).containsEntry("ISSUCCESS", "True");
    }

    private static CapabilityRequest request(String svcName) {
        return new CapabilityRequest("ji-1", "corr-1", "verification", "n_verify",
                Map.of("registrationNumber", "AB12CD1234"), Map.of(), svcName, "idem-1");
    }

    private static VerificationAdapter adapter(String svcName, Runnable body) {
        return new VerificationAdapter() {
            @Override public String svcName() { return svcName; }
            @Override public Map<String, Object> call(ResolvedRoute route, Map<String, Object> req) {
                body.run();
                return Map.of();
            }
        };
    }
}
