package com.idfcfirstbank.integration.platform.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard for confirmed delivery (Phase 1 item 2): a send must be
 * confirmed, and a broker failure or ack timeout must surface as an exception —
 * never be silently ignored the way a discarded {@code send(...)} future is.
 */
class KafkaDeliveryTest {

    @Test
    void returnsResultWhenSendSucceeds() {
        String result = KafkaDelivery.confirm(CompletableFuture.completedFuture("ok"), Duration.ofSeconds(1));
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void throwsWhenSendFails() {
        CompletableFuture<String> failed = CompletableFuture.failedFuture(new IllegalStateException("broker down"));
        assertThatThrownBy(() -> KafkaDelivery.confirm(failed, Duration.ofSeconds(1)))
                .isInstanceOf(KafkaPublishException.class)
                .hasMessageContaining("broker down");
    }

    @Test
    void throwsWhenDeliveryNotConfirmedInTime() {
        CompletableFuture<String> neverCompletes = new CompletableFuture<>();
        assertThatThrownBy(() -> KafkaDelivery.confirm(neverCompletes, Duration.ofMillis(50)))
                .isInstanceOf(KafkaPublishException.class)
                .hasMessageContaining("not confirmed");
    }
}
