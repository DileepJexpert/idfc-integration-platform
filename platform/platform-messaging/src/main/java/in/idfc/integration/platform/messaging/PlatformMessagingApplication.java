package in.idfc.integration.platform.messaging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 STUB. Starts and serves /actuator/health; no business logic yet.
 * The real {@code platform-messaging} capability is implemented in a later slice.
 */
@SpringBootApplication
public class PlatformMessagingApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformMessagingApplication.class, args);
    }
}
