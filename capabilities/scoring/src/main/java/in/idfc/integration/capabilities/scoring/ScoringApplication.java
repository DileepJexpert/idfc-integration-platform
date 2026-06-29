package in.idfc.integration.capabilities.scoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 STUB. Starts and serves /actuator/health; no business logic yet.
 * The real {@code scoring} capability is implemented in a later slice.
 */
@SpringBootApplication
public class ScoringApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScoringApplication.class, args);
    }
}
