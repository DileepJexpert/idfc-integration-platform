package com.idfcfirstbank.integration.brandrouter.adapter.in.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.brandrouter.application.BrandRouterService;
import com.idfcfirstbank.integration.brandrouter.config.BrandRouterProperties;
import com.idfcfirstbank.integration.brandrouter.domain.ActiveMqPort;
import com.idfcfirstbank.integration.brandrouter.domain.KafkaResponsePort;
import com.idfcfirstbank.integration.brandrouter.domain.RoutingDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * IN adapter: consume {@code brand.wrapper.topic}, extract {@code Brand__r.Name},
 * route by config. On a Kafka send failure, fall back to ActiveMQ (BRD §6).
 */
@Component
public class BrandWrapperConsumer {

    private static final Logger log = LoggerFactory.getLogger(BrandWrapperConsumer.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final BrandRouterService router = new BrandRouterService();
    private final KafkaResponsePort kafka;
    private final ActiveMqPort activeMq;
    private final ObjectMapper objectMapper;
    private final BrandRouterProperties props;

    public BrandWrapperConsumer(KafkaResponsePort kafka, ActiveMqPort activeMq,
                                ObjectMapper objectMapper, BrandRouterProperties props) {
        this.kafka = kafka;
        this.activeMq = activeMq;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @KafkaListener(topics = "${idfc.brand-router.wrapper-topic:brand.wrapper.topic}",
            groupId = "${idfc.brand-router.group:brand-router}")
    public void onMessage(String message) {
        try {
            String brand = extractBrand(message);
            RoutingDecision decision = router.route(brand, message, Set.copyOf(props.getPartitions()));
            if (decision.target() == RoutingDecision.Target.KAFKA) {
                try {
                    kafka.send(decision.key(), decision.payload());
                } catch (Exception kafkaFailure) {
                    log.warn("kafka send failed for brand {} — ActiveMQ fallback", brand, kafkaFailure);
                    activeMq.send(router.route(brand, message, Set.of()).payload()); // XML fallback
                }
            } else {
                activeMq.send(decision.payload());
            }
        } catch (Exception e) {
            log.error("brand-router could not process: {}", message, e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractBrand(String message) throws Exception {
        Map<String, Object> root = objectMapper.readValue(message, MAP);
        Object brandR = root.get("Brand__r");
        if (brandR instanceof Map<?, ?> m && m.get("Name") != null) {
            return String.valueOf(((Map<String, Object>) m).get("Name"));
        }
        return root.get("brand") == null ? null : String.valueOf(root.get("brand"));
    }
}
