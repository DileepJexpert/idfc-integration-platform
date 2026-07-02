package com.idfcfirstbank.integration.digitaledge.adapter.in.rest;

import com.idfcfirstbank.integration.digitaledge.application.ApplicationStatusStore;
import com.idfcfirstbank.integration.digitaledge.config.DigitalEdgeProperties;
import com.idfcfirstbank.integration.digitaledge.config.DigitalEdgeProperties.Partner;
import com.idfcfirstbank.integration.digitaledge.config.PartnerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 regression: the status endpoint must authenticate the partner AND
 * tenant-scope the read — CRED polling a FLIPKART applicationId reads as 404,
 * indistinguishable from "no such application" (no id probing).
 */
class DigitalStatusControllerAuthTest {

    private final ApplicationStatusStore store = new ApplicationStatusStore();
    private final DigitalStatusController controller = new DigitalStatusController(
            store, new PartnerRegistry(new DigitalEdgeProperties(
                    List.of(new Partner("CRED", "cred-token", null),
                            new Partner("FLIPKART", "flipkart-token", null)),
                    null, null, null, 0)));

    DigitalStatusControllerAuthTest() {
        store.register("REF-1", "APP-1", "CRED");
    }

    @Test
    void missingOrUnknownTokenIs401() {
        assertThat(controller.status(null, "APP-1").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.status("who-dis", "APP-1").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aPartnerReadsTheirOwnApplication() {
        var response = controller.status("cred-token", "APP-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().partner()).isEqualTo("CRED");
        assertThat(response.getBody().outcome()).isEqualTo("PENDING");
    }

    @Test
    void anotherPartnersApplicationReadsAs404NotTheirs() {
        assertThat(controller.status("flipkart-token", "APP-1").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.status("cred-token", "APP-does-not-exist").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
