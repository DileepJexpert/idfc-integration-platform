package in.idfc.integration.platform.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder test for the Slice 1 stub. Kept framework-free so the stub builds
 * fast and deterministically; the real module gets behavioural tests later.
 */
class PlatformConfigApplicationTests {
    @Test
    void stubModuleIsPresent() {
        assertThat(PlatformConfigApplication.class.getPackageName()).isEqualTo("in.idfc.integration.platform.config");
    }
}
