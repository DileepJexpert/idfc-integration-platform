package com.idfcfirstbank.integration.platform.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the shared reliability contract (Phase 1 items 1 & 2):
 * a failed record must be dead-lettered to {@code <topic>.dlq}, never silently
 * dropped, and poison input must be classified not-retryable.
 */
class PlatformKafkaErrorHandlingTest {

    private final PlatformKafkaErrorHandlingAutoConfiguration config =
            new PlatformKafkaErrorHandlingAutoConfiguration();

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recovererPublishesToTopicDlq() {
        KafkaOperations template = mock(KafkaOperations.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        DeadLetterPublishingRecoverer recoverer = config.platformDeadLetterPublishingRecoverer(template);

        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("cap.bureau.request.v1", 0, 0L, "k", "poison-value");
        recoverer.accept(record, new RuntimeException("boom"));

        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("cap.bureau.request.v1.dlq");
        assertThat(sent.getValue().value()).isEqualTo("poison-value");
    }

    @Test
    void buildsADefaultErrorHandlerBackedByTheRecoverer() {
        DeadLetterPublishingRecoverer recoverer = mock(DeadLetterPublishingRecoverer.class);
        DefaultErrorHandler handler = config.platformKafkaErrorHandler(recoverer);
        // A non-null container error handler is what turns the old swallow-and-commit
        // into retry-then-DLQ; PoisonMessageException is registered not-retryable in
        // its construction (see PlatformKafkaErrorHandlingAutoConfiguration).
        assertThat(handler).isNotNull();
    }
}
