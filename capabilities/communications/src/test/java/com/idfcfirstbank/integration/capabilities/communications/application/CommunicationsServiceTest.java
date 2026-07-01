package com.idfcfirstbank.integration.capabilities.communications.application;

import com.idfcfirstbank.integration.capabilities.communications.adapter.out.store.InMemorySentSmsStore;
import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.SmsSenderPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SENDSMS action over the OPAQUE Task body the edge carries: the capability
 * reads Mobile__c/Description itself (it owns the payload contract), sends once,
 * and is idempotent on Notification/Id so a redelivered OTP is not re-sent.
 */
class CommunicationsServiceTest {

    private record Sent(String to, String body) {}

    private final List<Sent> sends = new ArrayList<>();
    private final SmsSenderPort recorder = (to, body) -> sends.add(new Sent(to, body));
    private final CommunicationsService service = new CommunicationsService(recorder, new InMemorySentSmsStore());

    private static Map<String, Object> smsEnvelope(String notificationId, String mobile, String desc) {
        return Map.of(
                "type", "SENDSMS",
                "notificationId", notificationId,
                "payload", Map.of("Type", "OTP", "Mobile__c", mobile, "Description", desc));
    }

    @Test
    void sendsTheSmsReadingMobileAndDescriptionFromTheOpaqueTaskBody() {
        service.onSmsRequest(smsEnvelope("04lC-1", "9894873985", "731517 is OTP ..."));

        assertThat(sends).singleElement().satisfies(s -> {
            assertThat(s.to()).isEqualTo("9894873985");
            assertThat(s.body()).isEqualTo("731517 is OTP ...");
        });
    }

    @Test
    void redeliveryOfTheSameNotificationDoesNotResendTheOtp() {
        service.onSmsRequest(smsEnvelope("04lC-1", "9894873985", "731517 is OTP ..."));
        service.onSmsRequest(smsEnvelope("04lC-1", "9894873985", "731517 is OTP ..."));

        assertThat(sends).as("idempotent on Notification/Id").hasSize(1);
    }

    @Test
    void malformedBodyWithoutMobileIsSkippedNotSent() {
        service.onSmsRequest(Map.of("type", "SENDSMS", "notificationId", "04lC-2",
                "payload", Map.of("Type", "OTP", "Description", "no mobile here")));

        assertThat(sends).isEmpty();
    }
}
