package com.idfcfirstbank.integration.platform.journeyregistry.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A0's rule applied to the control plane: a registry with no service token would
 * let anyone publish what the engine runs — a missing secret refuses to start.
 */
class RegistryPropertiesFailClosedTest {

    @Test
    void missingAuthTokenRefusesToStart() {
        assertThatThrownBy(() -> new RegistryProperties(null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REGISTRY_AUTH_TOKEN");
        assertThatThrownBy(() -> new RegistryProperties(null, "   ", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses to start");
    }

    @Test
    void explicitTokenStartsWithSafeDefaults() {
        RegistryProperties props = new RegistryProperties(null, "a-real-secret", null, null);

        assertThat(props.store()).isEqualTo("in-memory");
        assertThat(props.usesAerospike()).isFalse();
        assertThat(props.corsAllowedOriginPatterns()).containsExactly("http://localhost:[*]");
        assertThat(props.aerospike().host()).isEqualTo("localhost");
        assertThat(props.aerospike().metaSet()).isEqualTo("journey_meta");
        assertThat(props.aerospike().versionSet()).isEqualTo("journey_version");
    }
}
