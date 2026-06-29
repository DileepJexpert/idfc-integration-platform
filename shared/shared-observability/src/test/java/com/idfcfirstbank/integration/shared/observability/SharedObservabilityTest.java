package com.idfcfirstbank.integration.shared.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharedObservabilityTest {
    @Test
    void libraryModuleIsPresent() {
        assertThat(SharedObservability.class.getPackageName()).isEqualTo("com.idfcfirstbank.integration.shared.observability");
    }
}
