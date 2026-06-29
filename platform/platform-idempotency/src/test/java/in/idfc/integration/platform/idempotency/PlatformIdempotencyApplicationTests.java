package in.idfc.integration.platform.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder test for the Slice 1 stub. Kept framework-free so the stub builds
 * fast and deterministically; the real module gets behavioural tests later.
 */
class PlatformIdempotencyApplicationTests {
    @Test
    void stubModuleIsPresent() {
        assertThat(PlatformIdempotencyApplication.class.getPackageName()).isEqualTo("in.idfc.integration.platform.idempotency");
    }
}
