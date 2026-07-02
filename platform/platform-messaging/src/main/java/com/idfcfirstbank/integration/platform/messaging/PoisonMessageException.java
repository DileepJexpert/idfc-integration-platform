package com.idfcfirstbank.integration.platform.messaging;

/**
 * A message that can never succeed no matter how often it is retried — a
 * structurally invalid / undeserializable record. Kafka listeners throw this
 * (instead of swallowing) so the shared {@code DefaultErrorHandler} routes it
 * STRAIGHT to {@code <topic>.dlq} with no retry loop (it is registered as a
 * not-retryable exception in {@link PlatformKafkaErrorHandlingAutoConfiguration}).
 *
 * <p>Contrast with a transient/business failure (broker down, vendor timeout,
 * store blip): those must NOT be wrapped in this type — they should propagate as
 * their natural exception so the error handler retries with backoff before
 * dead-lettering.
 */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }

    public PoisonMessageException(String message) {
        super(message);
    }
}
