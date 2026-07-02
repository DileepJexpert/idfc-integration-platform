package com.idfcfirstbank.integration.edges.sfdcingress.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 fail-closed gate: the internet-facing edge must REFUSE TO START when
 * its auth token is missing — the old {@code ${SFDC_EDGE_TOKEN:dev-token}}
 * default silently fell open to a token anyone can read from the repo.
 */
class EdgePropertiesFailClosedTest {

    private static EdgeProperties props(EdgeProperties.Auth auth) {
        return new EdgeProperties(auth, 5, 1, 60, "orig.sfdc.dlq.v1", null, List.of(), List.of());
    }

    @Test
    void missingAuthSectionRefusesToStart() {
        assertThatThrownBy(() -> props(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SFDC_EDGE_TOKEN")
                .hasMessageContaining("refuses to start");
    }

    @Test
    void blankTokenRefusesToStart() {
        assertThatThrownBy(() -> props(new EdgeProperties.Auth("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no fail-open default");
        assertThatThrownBy(() -> props(new EdgeProperties.Auth(null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void explicitTokenStarts() {
        assertThat(props(new EdgeProperties.Auth("some-secret")).auth().expectedToken())
                .isEqualTo("some-secret");
    }
}
