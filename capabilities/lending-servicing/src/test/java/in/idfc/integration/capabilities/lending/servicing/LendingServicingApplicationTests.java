package in.idfc.integration.capabilities.lending.servicing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder test for the Slice 1 stub. Kept framework-free so the stub builds
 * fast and deterministically; the real module gets behavioural tests later.
 */
class LendingServicingApplicationTests {
    @Test
    void stubModuleIsPresent() {
        assertThat(LendingServicingApplication.class.getPackageName()).isEqualTo("in.idfc.integration.capabilities.lending.servicing");
    }
}
