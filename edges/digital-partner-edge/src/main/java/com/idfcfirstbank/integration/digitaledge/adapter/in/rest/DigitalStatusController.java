package com.idfcfirstbank.integration.digitaledge.adapter.in.rest;

import com.idfcfirstbank.integration.digitaledge.application.ApplicationStatusStore;
import com.idfcfirstbank.integration.digitaledge.config.DigitalEdgeProperties.Partner;
import com.idfcfirstbank.integration.digitaledge.config.PartnerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Partner status/polling endpoint — the pull side of the decision return (the
 * push side is {@code PartnerCallbackPort}). Mirrors SFDC's record-state read.
 *
 * <p>Authenticated with the partner token (Phase 5) and TENANT-SCOPED: a partner
 * sees only their own applications. Another partner's applicationId reads as 404
 * — indistinguishable from "no such application", so ids can't be probed.
 */
@RestController
@RequestMapping("/api/v1/digital")
public class DigitalStatusController {

    private final ApplicationStatusStore statusStore;
    private final PartnerRegistry partnerRegistry;

    public DigitalStatusController(ApplicationStatusStore statusStore, PartnerRegistry partnerRegistry) {
        this.statusStore = statusStore;
        this.partnerRegistry = partnerRegistry;
    }

    @GetMapping("/applications/{applicationId}")
    public ResponseEntity<ApplicationStatusStore.Status> status(
            @RequestHeader(value = "X-Partner-Token", required = false) String partnerToken,
            @PathVariable String applicationId) {

        Optional<Partner> partner = partnerRegistry.resolveByToken(partnerToken);
        if (partner.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return statusStore.byApplicationId(applicationId)
                .filter(status -> partner.get().code().equals(status.partner()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
