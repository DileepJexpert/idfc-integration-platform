package com.idfcfirstbank.integration.capabilities.bureau;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder test for the Slice 1 stub. Kept framework-free so the stub builds
 * fast and deterministically; the real module gets behavioural tests later.
 */
class BureauApplicationTests {
    @Test
    void stubModuleIsPresent() {
        assertThat(BureauApplication.class.getPackageName()).isEqualTo("com.idfcfirstbank.integration.capabilities.bureau");
    }
}
