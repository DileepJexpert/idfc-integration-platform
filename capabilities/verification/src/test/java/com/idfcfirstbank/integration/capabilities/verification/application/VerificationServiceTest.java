package com.idfcfirstbank.integration.capabilities.verification.application;

import com.idfcfirstbank.integration.capabilities.verification.adapter.out.echo.EchoVerificationAdapter;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.route.ConfigRouteResolver;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties.Retry;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties.Route;
import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step-1 shell proof: the endpoint comes from the CONTROL PLANE (not the message),
 * only allow-listed targets are callable, the svcName selects the adapter + mapper,
 * and an NA mapper is raw-JSON passthrough. Exercised with the trivial ECHO svcName.
 */
class VerificationServiceTest {

    private final VerificationProperties props = new VerificationProperties(
            List.of(
                    new Route("ECHO", "http://echo.mock/echo", "NONE"),
                    new Route("BADHOST", "http://evil.example/steal", "NONE")),   // registered but NOT allow-listed
            List.of("echo.mock"),
            new Retry(3, 0, 0, false),
            "cap.verification.dlq.v1", "sfdc.response.notify.v1");

    private final VerificationService service = new VerificationService(
            new ConfigRouteResolver(props),
            new AdapterRegistry(List.of(new EchoVerificationAdapter())),
            new MapperRegistry());   // all passthrough in step 1

    @Test
    void echoResolvesEndpointFromControlPlaneAndReturnsSuccessEnvelope() {
        Map<String, Object> env = service.verify("ECHO", Map.of("registrationNumber", "AB12CD1234", "consent", "Y"));

        assertThat(env).containsEntry("ISSUCCESS", "True");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) env.get("DATA");
        // The endpoint the adapter used came from OUR config, not the request.
        assertThat(data).containsEntry("resolvedEndpoint", "http://echo.mock/echo");
        // NA/passthrough mapper: the request rode through unmodified.
        @SuppressWarnings("unchecked")
        Map<String, Object> echoed = (Map<String, Object>) data.get("echoed");
        assertThat(echoed).containsEntry("registrationNumber", "AB12CD1234").containsEntry("consent", "Y");
    }

    @Test
    void unknownSvcNameHasNoControlPlaneRouteAndIsRejected() {
        assertThatThrownBy(() -> service.verify("NOT_REGISTERED", Map.of()))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> assertThat(((VerificationException) e).errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }

    @Test
    void aResolvedTargetNotOnTheAllowListIsRefusedAntiSsrf() {
        assertThatThrownBy(() -> service.verify("BADHOST", Map.of()))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("allow-listed");
    }

    @Test
    void altFieldNameToleranceIsPreserved() {
        // registrationNumber OR reg_no — the wrapper behaviour mappers rely on.
        assertThat(MapperSupport.firstOf(Map.of("reg_no", "XY99ZZ0000"), "registrationNumber", "reg_no"))
                .isEqualTo("XY99ZZ0000");
        assertThat(MapperSupport.firstOf(Map.of("registrationNumber", "AB12CD1234"), "registrationNumber", "reg_no"))
                .isEqualTo("AB12CD1234");
    }
}
