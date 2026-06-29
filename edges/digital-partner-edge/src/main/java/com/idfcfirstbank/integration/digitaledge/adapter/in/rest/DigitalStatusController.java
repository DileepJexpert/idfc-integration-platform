package com.idfcfirstbank.integration.digitaledge.adapter.in.rest;

import com.idfcfirstbank.integration.digitaledge.application.ApplicationStatusStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner status/polling endpoint — the pull side of the decision return (the
 * push side is {@code PartnerCallbackPort}). Mirrors SFDC's record-state read.
 */
@RestController
@RequestMapping("/api/v1/digital")
public class DigitalStatusController {

    private final ApplicationStatusStore statusStore;

    public DigitalStatusController(ApplicationStatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @GetMapping("/applications/{applicationId}")
    public ResponseEntity<ApplicationStatusStore.Status> status(@PathVariable String applicationId) {
        return statusStore.byApplicationId(applicationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
