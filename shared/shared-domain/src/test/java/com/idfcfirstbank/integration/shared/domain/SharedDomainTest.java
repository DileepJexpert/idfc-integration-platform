package com.idfcfirstbank.integration.shared.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharedDomainTest {
    @Test
    void libraryModuleIsPresent() {
        assertThat(SharedDomain.class.getPackageName()).isEqualTo("com.idfcfirstbank.integration.shared.domain");
    }
}
