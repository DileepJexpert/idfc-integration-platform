package in.idfc.integration.orchestration.originationjourney;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 STUB. Starts and serves /actuator/health; no business logic yet.
 * The real {@code origination-journey} capability is implemented in a later slice.
 */
@SpringBootApplication
public class OriginationJourneyApplication {
    public static void main(String[] args) {
        SpringApplication.run(OriginationJourneyApplication.class, args);
    }
}
