package com.idfcfirstbank.integration.platform.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The platform's ONE Kafka reliability contract, auto-applied to every app that
 * depends on {@code platform-messaging}. Spring Boot wires a single context
 * {@link CommonErrorHandler} bean into its auto-configured
 * {@code ConcurrentKafkaListenerContainerFactory}, so merely having this module on
 * the classpath gives a listener:
 *
 * <ul>
 *   <li><b>at-least-once, not at-most-once</b>: a processing failure is retried
 *       with bounded exponential backoff instead of being swallowed and committed;</li>
 *   <li><b>dead-letter on exhaustion</b>: after the retries the record is
 *       published to {@code <topic>.dlq} (partition chosen by the producer),
 *       preserving the payload for inspection/replay rather than dropping it;</li>
 *   <li><b>poison straight to DLQ</b>: {@link PoisonMessageException} (thrown by
 *       listeners on undeserializable input) is not-retryable, so structurally
 *       bad records go to the DLQ immediately without spinning the retry loop.</li>
 * </ul>
 *
 * <p>For this to hold the container must own offset commits (Spring's default;
 * do NOT set {@code enable-auto-commit: true}, which would commit regardless of
 * the handler's seeks). Any app may override either bean.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, DefaultErrorHandler.class})
public class PlatformKafkaErrorHandlingAutoConfiguration {

    /** DLQ topic suffix appended to the source topic. */
    static final String DLQ_SUFFIX = ".dlq";

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_INTERVAL_MS = 500L;

    /**
     * Routes exhausted/poison records to {@code <sourceTopic>.dlq}. Partition -1
     * lets the producer choose, so the DLQ topic needs no fixed partition count.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaOperations.class)
    public DeadLetterPublishingRecoverer platformDeadLetterPublishingRecoverer(KafkaOperations<?, ?> template) {
        return new DeadLetterPublishingRecoverer(template,
                (ConsumerRecord<?, ?> record, Exception ex) ->
                        new TopicPartition(record.topic() + DLQ_SUFFIX, -1));
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    @ConditionalOnBean(DeadLetterPublishingRecoverer.class)
    public DefaultErrorHandler platformKafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        // Bounded retry: MAX_RETRIES attempts at a fixed interval, then dead-letter.
        FixedBackOff backOff = new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Undeserializable / structurally invalid records can never succeed -> DLQ now, no retries.
        handler.addNotRetryableExceptions(PoisonMessageException.class);
        return handler;
    }
}
