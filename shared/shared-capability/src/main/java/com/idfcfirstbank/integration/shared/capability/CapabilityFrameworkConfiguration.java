package com.idfcfirstbank.integration.shared.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.platform.messaging.KafkaDelivery;
import com.idfcfirstbank.integration.platform.messaging.PoisonMessageException;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityTopics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

/**
 * The Kafka shell of the capability framework. Given the app's single
 * {@link Capability} bean it wires — with ZERO per-capability code — a listener
 * on {@code cap.<key>.request.v1} that runs the idempotent {@link CapabilityDispatcher}
 * and publishes the result to {@code cap.<key>.response.v1}. Auto-configured for
 * any app on the classpath (see META-INF/spring/...AutoConfiguration.imports).
 */
@AutoConfiguration
@ConditionalOnBean(Capability.class)
public class CapabilityFrameworkConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CapabilityIdempotencyStore capabilityIdempotencyStore() {
        return new InMemoryCapabilityIdempotencyStore();
    }

    @Bean
    public CapabilityDispatcher capabilityDispatcher(Capability capability,
                                                     CapabilityIdempotencyStore store) {
        return new CapabilityDispatcher(capability, store);
    }

    @Bean(destroyMethod = "stop")
    public ConcurrentMessageListenerContainer<String, String> capabilityRequestContainer(
            Capability capability,
            CapabilityDispatcher dispatcher,
            ConcurrentKafkaListenerContainerFactory<String, String> factory,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {

        String requestTopic = CapabilityTopics.request(capability.key());
        String responseTopic = CapabilityTopics.response(capability.key());

        ConcurrentMessageListenerContainer<String, String> container =
                factory.createContainer(requestTopic);
        container.getContainerProperties().setGroupId("cap-" + capability.key());
        container.setupMessageListener((MessageListener<String, String>) record -> {
            CapabilityRequest req;
            try {
                req = objectMapper.readValue(record.value(), CapabilityRequest.class);
            } catch (Exception e) {
                // Undeserializable input can never succeed: straight to <topic>.dlq, no retry.
                throw new PoisonMessageException(
                        "capability " + capability.key() + " could not deserialize request", e);
            }
            // Dispatch (idempotent) then CONFIRM the response send. A processing or
            // delivery failure propagates to the container error handler (retry then
            // DLQ) instead of being swallowed-and-committed — the offset is only
            // committed once the response is durably published.
            CapabilityResponse resp = dispatcher.handle(req);
            String payload;
            try {
                payload = objectMapper.writeValueAsString(resp);
            } catch (Exception e) {
                throw new PoisonMessageException(
                        "capability " + capability.key() + " could not serialize response", e);
            }
            KafkaDelivery.confirm(kafkaTemplate.send(responseTopic, resp.nodeId(), payload));
        });
        container.start();
        return container;
    }
}
