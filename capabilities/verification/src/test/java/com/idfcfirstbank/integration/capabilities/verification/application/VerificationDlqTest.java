package com.idfcfirstbank.integration.capabilities.verification.application;

import com.idfcfirstbank.integration.capabilities.verification.adapter.out.route.ConfigRouteResolver;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties.Retry;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties.Route;
import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.SfdcNotifyPort;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationAdapter;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationDlqPort;
import com.idfcfirstbank.integration.shared.capability.Backoff;
import com.idfcfirstbank.integration.shared.capability.RetryExecutor;
import com.idfcfirstbank.integration.shared.capability.RetryPolicy;
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
 * Spec v2 §C: classified retry (not blind), and on TERMINAL technical failure DLQ + notifySfdc
 * (never silent-lost). Business declines are NOT technical failures — they are HTTP-200 envelopes
 * that stay OK here and are declined by the journey branch.
 */
class VerificationDlqTest {

    private final List<String> dlq = new ArrayList<>();
    private final List<String> notified = new ArrayList<>();
    private final VerificationDlqPort recordingDlq = (req, reason) -> dlq.add(req.operation() + ":" + reason);
    private final SfdcNotifyPort recordingNotify = (req, reason) -> notified.add(req.operation() + ":" + reason);

    private final AtomicInteger transientCalls = new AtomicInteger();
    private final AtomicInteger permanentCalls = new AtomicInteger();
    private final AtomicInteger ambiguousCalls = new AtomicInteger();

    private final VerificationProperties props = new VerificationProperties(
            List.of(new Route("ECHO", "http://echo.mock/echo", "NONE"),
                    new Route("FAIL_T", "http://karza.mock/t", "OAUTH_BEARER"),
                    new Route("FAIL_P", "http://karza.mock/p", "OAUTH_BEARER"),
                    new Route("FAIL_A", "http://karza.mock/a", "OAUTH_BEARER"),
                    new Route("BIZ_DECLINE", "http://karza.mock/b", "OAUTH_BEARER")),
            List.of("echo.mock", "karza.mock"),
            new Retry(3, 200, 5000, true),
            "cap.verification.dlq.v1", "sfdc.response.notify.v1");

    private final VerificationService service = new VerificationService(
            new ConfigRouteResolver(props),
            new AdapterRegistry(List.of(
                    throwing("FAIL_T", ErrorClass.TRANSIENT, "S503", transientCalls),
                    throwing("FAIL_P", ErrorClass.PERMANENT, "S400", permanentCalls),
                    throwing("FAIL_A", ErrorClass.AMBIGUOUS, "READ_TIMEOUT", ambiguousCalls),
                    returning("BIZ_DECLINE", Map.of("result", List.of(Map.of("result",
                            Map.of("rcStatus", "ACTIVE", "blackListStatus", "BLACKLIST")))))
            )),
            new MapperRegistry());

    // 3 attempts, no real sleep (deterministic), idempotent reads (AMBIGUOUS retried).
    private final VerificationDispatcher dispatcher = new VerificationDispatcher(
            service, recordingDlq, recordingNotify,
            new RetryExecutor(millis -> { }), RetryPolicy.idempotentReads(3, Backoff.fixed(0)));

    @Test
    void permanentFailsFastToDlqPlusNotify() {
        CapabilityResponse r = dispatcher.handle(request("FAIL_P"));
        assertThat(permanentCalls.get()).isEqualTo(1);          // no retry
        assertThat(dlq).hasSize(1);
        assertThat(notified).as("SFDC notified of the FAILED run").hasSize(1);
        assertThat(r.errorClass()).isEqualTo(ErrorClass.PERMANENT);
    }

    @Test
    void transientRetriesThenDlqPlusNotify() {
        CapabilityResponse r = dispatcher.handle(request("FAIL_T"));
        assertThat(transientCalls.get()).isEqualTo(3);          // retried to maxAttempts
        assertThat(dlq).hasSize(1);
        assertThat(notified).hasSize(1);
        assertThat(r.status()).isEqualTo(CapabilityStatus.ERROR);
    }

    @Test
    void ambiguousIsRetriedBecauseVerificationIsIdempotent() {
        dispatcher.handle(request("FAIL_A"));
        assertThat(ambiguousCalls.get()).as("idempotent read -> AMBIGUOUS retried").isEqualTo(3);
        assertThat(dlq).hasSize(1);
        assertThat(notified).hasSize(1);
    }

    @Test
    void businessDeclineStaysOkAndNeverTouchesDlqOrNotify() {
        // A 200-with-flags body: the service SUCCEEDS; the journey branch declines it.
        CapabilityResponse r = dispatcher.handle(request("BIZ_DECLINE"));
        assertThat(r.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(r.result()).containsEntry("ISSUCCESS", "True");
        assertThat(dlq).as("business decline is NOT a technical failure").isEmpty();
        assertThat(notified).isEmpty();
    }

    private static CapabilityRequest request(String svcName) {
        return new CapabilityRequest("ji-1", "corr-1", "verification", "n_verify",
                Map.of("registrationNumber", "AB12CD1234"), Map.of(), svcName, "idem-1");
    }

    private static VerificationAdapter throwing(String svcName, ErrorClass ec, String code, AtomicInteger calls) {
        return new VerificationAdapter() {
            @Override public String svcName() { return svcName; }
            @Override public Map<String, Object> call(ResolvedRoute route, Map<String, Object> req) {
                calls.incrementAndGet();
                throw new VerificationException(ec, code, "downstream failure");
            }
        };
    }

    private static VerificationAdapter returning(String svcName, Map<String, Object> body) {
        return new VerificationAdapter() {
            @Override public String svcName() { return svcName; }
            @Override public Map<String, Object> call(ResolvedRoute route, Map<String, Object> req) { return body; }
        };
    }
}
