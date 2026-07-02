package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest;

import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.SfdcDecisionController.DecisionRequest;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.SfdcDecisionController.DecisionResponse;
import com.idfcfirstbank.integration.edges.sfdcingress.application.DecisionService;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.Decision;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 5 regression: the decision callback flips customer-visible state and
 * fires the SFDC push-back — it must reject unauthenticated callers BEFORE any
 * business code runs, with the SAME edge token as the SOAP inbound.
 */
class SfdcDecisionControllerAuthTest {

    private final DecisionService decisionService = mock(DecisionService.class);
    private final SfdcDecisionController controller =
            new SfdcDecisionController(decisionService, "edge-secret"::equals);

    @Test
    void missingOrWrongTokenIs401AndNeverTouchesTheService() {
        ResponseEntity<DecisionResponse> missing = controller.decide(
                null, "corr-1", new DecisionRequest("ntf-1", "APPROVED", "APP-1", null));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<DecisionResponse> wrong = controller.decide(
                "not-the-secret", "corr-1", new DecisionRequest("ntf-1", "APPROVED", "APP-1", null));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        verifyNoInteractions(decisionService);
    }

    @Test
    void validTokenAppliesTheDecision() {
        when(decisionService.applyDecision(eq("ntf-1"), any(Decision.class), anyString())).thenReturn(true);

        ResponseEntity<DecisionResponse> ok = controller.decide(
                "edge-secret", "corr-1", new DecisionRequest("ntf-1", "APPROVED", "APP-1", null));

        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().pushed()).isTrue();
        verify(decisionService).applyDecision(eq("ntf-1"), any(Decision.class), eq("corr-1"));
    }
}
