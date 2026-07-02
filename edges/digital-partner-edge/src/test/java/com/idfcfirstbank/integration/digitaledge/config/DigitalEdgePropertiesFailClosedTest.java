package com.idfcfirstbank.integration.digitaledge.config;

import com.idfcfirstbank.integration.digitaledge.config.DigitalEdgeProperties.Partner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 fail-closed gate: a configured partner whose token secret is missing
 * must refuse to start — the old {@code ${CRED_TOKEN:cred-dev-token}} defaults
 * silently fell open to tokens anyone can read from the repo.
 */
class DigitalEdgePropertiesFailClosedTest {

    private static DigitalEdgeProperties props(List<Partner> partners) {
        return new DigitalEdgeProperties(partners, List.of(), null, "orig.decision.v1", 60);
    }

    @Test
    void partnerWithBlankTokenRefusesToStart() {
        assertThatThrownBy(() -> props(List.of(new Partner("CRED", "", "http://cb"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRED")
                .hasMessageContaining("refuses to start");
        assertThatThrownBy(() -> props(List.of(
                new Partner("CRED", "cred-secret", "http://cb"),
                new Partner("GROWW", null, "http://cb"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GROWW");
    }

    @Test
    void allTokensPresentStarts() {
        DigitalEdgeProperties props = props(List.of(
                new Partner("CRED", "cred-secret", "http://cb"),
                new Partner("GROWW", "groww-secret", "http://cb")));
        assertThat(props.partners()).hasSize(2);
    }

    @Test
    void noPartnersConfiguredStillStarts() {
        // An empty registry is a valid (if useless) configuration — nothing fails open.
        assertThat(props(List.of()).partners()).isEmpty();
    }
}
