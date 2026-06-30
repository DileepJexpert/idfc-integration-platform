package com.idfcfirstbank.integration.brandrouter.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.brandrouter.config.BrandRouterProperties;
import com.idfcfirstbank.integration.brandrouter.domain.ActiveMqPort;
import com.idfcfirstbank.integration.brandrouter.domain.KafkaResponsePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/** Out adapters: real Kafka producer; ActiveMQ MOCKED (a real JMS broker is a
 * config-driven later step, §6). */
@Configuration
public class BrandRouterAdapters {

    private static final Logger log = LoggerFactory.getLogger(BrandRouterAdapters.class);

    @Bean
    KafkaResponsePort kafkaResponsePort(KafkaTemplate<String, String> template, BrandRouterProperties props) {
        return (key, payload) -> template.send(props.getResponseTopic(), key, payload);
    }

    @Bean
    ActiveMqPort activeMqPort(ObjectMapper objectMapper) {
        // mock: a real JMS ConnectionFactory/JmsTemplate swaps in behind this port.
        return xml -> log.info("activemq.send (mock) {}", xml);
    }
}
