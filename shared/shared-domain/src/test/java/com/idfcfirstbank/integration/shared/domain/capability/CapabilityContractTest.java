package com.idfcfirstbank.integration.shared.domain.capability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins THE CAPABILITY CONTRACT's topic-naming convention. Engine and every
 * capability derive topics from this — a change here is a breaking contract change.
 */
class CapabilityContractTest {

    @Test
    void topicsFollowTheCapPrefixConvention() {
        assertThat(CapabilityTopics.request("scoring")).isEqualTo("cap.scoring.request.v1");
        assertThat(CapabilityTopics.response("scoring")).isEqualTo("cap.scoring.response.v1");
        assertThat(CapabilityTopics.request("lending-origination"))
                .isEqualTo("cap.lending-origination.request.v1");
    }

    @Test
    void recordsCarryTheContractFields() {
        CapabilityRequest req = new CapabilityRequest("ji", "corr", "bureau", "n_bureau",
                java.util.Map.of("pan", "X"), java.util.Map.of());
        assertThat(req.capabilityKey()).isEqualTo("bureau");

        CapabilityResponse resp = new CapabilityResponse("ji", "corr", "n_bureau", "bureau",
                CapabilityStatus.OK, java.util.Map.of("bureauScore", 780));
        assertThat(resp.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(resp.result()).containsEntry("bureauScore", 780);
    }
}
