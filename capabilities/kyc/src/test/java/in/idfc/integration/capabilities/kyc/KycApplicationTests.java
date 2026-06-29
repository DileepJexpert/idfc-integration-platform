package in.idfc.integration.capabilities.kyc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder test for the Slice 1 stub. Kept framework-free so the stub builds
 * fast and deterministically; the real module gets behavioural tests later.
 */
class KycApplicationTests {
    @Test
    void stubModuleIsPresent() {
        assertThat(KycApplication.class.getPackageName()).isEqualTo("in.idfc.integration.capabilities.kyc");
    }
}
