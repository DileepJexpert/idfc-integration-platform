package com.idfcfirstbank.integration.capabilities.communications.application;

import com.idfcfirstbank.integration.capabilities.communications.adapter.out.store.InMemorySentSmsStore;
import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.CommsHubPort;
import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.SendMeterPort;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard for Phase 1 item 4: the OTP is marked "sent" only AFTER a
 * successful send. A send failure releases the claim and propagates, so a
 * redelivery re-sends instead of the OTP being permanently suppressed — while a
 * successful send is still deduped against redelivery.
 */
class CommunicationsServiceOtpDeliveryTest {

    /** Runs the send inline (no metering concurrency needed for the test). */
    private static final SendMeterPort INLINE_METER = new SendMeterPort() {
        @Override public void meter(Runnable send) { send.run(); }
        @Override public int maxObservedConcurrency() { return 0; }
        @Override public long totalMetered() { return 0; }
    };

    /** A CommsHub that fails its first {@code failFirst} sends, then succeeds. */
    private static final class FlakyHub implements CommsHubPort {
        private final int failFirst;
        int attempts;
        int delivered;

        FlakyHub(int failFirst) {
            this.failFirst = failFirst;
        }

        @Override
        public void sendSms(String toMobile, String body) {
            attempts++;
            if (attempts <= failFirst) {
                throw new RuntimeException("commshub unavailable");
            }
            delivered++;
        }
    }

    private static Map<String, Object> envelope(String notificationId) {
        return Map.of(
                "notificationId", notificationId,
                "payload", Map.of("Mobile__c", "9990001111", "Description", "OTP 123456"));
    }

    @Test
    void failedSendReleasesClaimSoRedeliveryReSends() {
        var store = new InMemorySentSmsStore();
        var hub = new FlakyHub(1); // first send fails, second succeeds
        var service = new CommunicationsService(hub, INLINE_METER, store);

        // First delivery attempt fails and must PROPAGATE (not swallowed).
        assertThatThrownBy(() -> service.onSmsRequest(envelope("N1")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("commshub unavailable");

        // Redelivery of the SAME notificationId re-sends (claim was released) and succeeds.
        service.onSmsRequest(envelope("N1"));

        assertThat(hub.delivered).isEqualTo(1);
        assertThat(hub.attempts).isEqualTo(2);
    }

    @Test
    void successfulSendIsDedupedAgainstRedelivery() {
        var store = new InMemorySentSmsStore();
        var hub = new FlakyHub(0); // always succeeds
        var service = new CommunicationsService(hub, INLINE_METER, store);

        service.onSmsRequest(envelope("N1"));
        service.onSmsRequest(envelope("N1")); // redelivery — must be suppressed

        assertThat(hub.delivered).isEqualTo(1);
        assertThat(hub.attempts).isEqualTo(1);
    }
}
