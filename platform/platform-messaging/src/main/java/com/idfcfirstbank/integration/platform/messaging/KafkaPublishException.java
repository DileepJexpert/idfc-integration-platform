package com.idfcfirstbank.integration.platform.messaging;

/**
 * Thrown when a Kafka send could not be CONFIRMED — the broker rejected it, the
 * ack timed out, or the awaiting thread was interrupted. Producers raise this via
 * {@link KafkaDelivery#confirm} instead of firing and forgetting, so a delivery
 * failure surfaces to the caller (a listener's error handler, or an outbound
 * adapter's fallback) rather than being silently lost.
 */
public class KafkaPublishException extends RuntimeException {

    public KafkaPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
