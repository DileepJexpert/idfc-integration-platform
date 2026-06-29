package in.idfc.integration.capabilities.lending.origination;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 STUB. Starts and serves /actuator/health; no business logic yet.
 * The real {@code lending-origination} capability is implemented in a later slice.
 */
@SpringBootApplication
public class LendingOriginationApplication {
    public static void main(String[] args) {
        SpringApplication.run(LendingOriginationApplication.class, args);
    }
}
