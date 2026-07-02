package com.idfcfirstbank.integration.platform.messaging;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns a fire-and-forget {@code kafkaTemplate.send(...)} into a CONFIRMED send:
 * block on the returned future up to a bounded timeout and translate any failure
 * (broker rejection, ack timeout, interruption) into an unchecked
 * {@link KafkaPublishException}.
 *
 * <p>Why bounded: an unbounded {@code future.get()} can pin the calling thread for
 * the producer's full {@code delivery.timeout.ms} (up to minutes) during a broker
 * outage. A short, explicit ceiling lets callers classify the failure and react
 * (retry, fall back to ActiveMQ, or let a listener's error handler dead-letter)
 * instead of hanging or losing the message.
 */
public final class KafkaDelivery {

    /** Sensible default ceiling for a single confirmed send. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private KafkaDelivery() {
    }

    /** Confirm delivery of {@code send(...)}'s future, or throw {@link KafkaPublishException}. */
    public static <T> T confirm(CompletableFuture<T> sendFuture, Duration timeout) {
        try {
            return sendFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException("interrupted while awaiting Kafka delivery", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new KafkaPublishException("Kafka delivery failed: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new KafkaPublishException("Kafka delivery not confirmed within " + timeout, e);
        }
    }

    /** Confirm delivery using {@link #DEFAULT_TIMEOUT}. */
    public static <T> T confirm(CompletableFuture<T> sendFuture) {
        return confirm(sendFuture, DEFAULT_TIMEOUT);
    }
}
