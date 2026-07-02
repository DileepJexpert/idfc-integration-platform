package com.idfcfirstbank.integration.orchestration.originationjourney.config;

import com.idfcfirstbank.integration.orchestration.originationjourney.config.EngineProperties.Registry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A0's rule on the A2 seam: registry mode without an address/token must refuse
 * to start — it must NEVER quietly fall back to the classpath JAR (a silent
 * dual source of truth). Unknown source values fail closed too.
 */
class EnginePropertiesFailClosedTest {

    private static EngineProperties props(String source, Registry registry) {
        return new EngineProperties(source, null, registry, null, null, null, null);
    }

    @Test
    void registryModeWithoutABaseUrlRefusesToStart() {
        assertThatThrownBy(() -> props("registry", new Registry(null, "token", 0, 0, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    void registryModeWithoutATokenRefusesToStart() {
        assertThatThrownBy(() -> props("registry", new Registry("http://localhost:8104", " ", 0, 0, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth-token");
    }

    @Test
    void anUnknownJourneySourceRefusesToStart() {
        assertThatThrownBy(() -> props("s3-bucket", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("journey-source");
    }

    @Test
    void classpathModeNeedsNoRegistrySettings() {
        EngineProperties props = props(null, null);

        assertThat(props.journeySource()).isEqualTo("classpath");
        assertThat(props.usesRegistrySource()).isFalse();
        assertThat(props.journeyResources()).containsExactly("journeys/loan-origination.journey.json");
        assertThat(props.registry().refreshSeconds()).isEqualTo(30);
        assertThat(props.registry().connectTimeoutMs()).isEqualTo(3_000);
        assertThat(props.registry().readTimeoutMs()).isEqualTo(10_000);
    }

    @Test
    void registryModeWithUrlAndTokenStarts() {
        EngineProperties props = props("registry",
                new Registry("http://localhost:8104", "dev-registry-token", 5, 0, 0));

        assertThat(props.usesRegistrySource()).isTrue();
        assertThat(props.registry().refreshSeconds()).isEqualTo(5);
    }
}
