package in.idfc.integration.edges.sfdcingress.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.idfc.integration.edges.sfdcingress.domain.port.FinnOneMeterPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The Slice 1 backpressure harness consumer (§G). It consumes canonical envelopes
 * from the origination topics and calls the (mock) FinnOne stored proc. Its
 * container concurrency is bounded to N (see KafkaConfig), and the origination
 * topic is created with N partitions, so AT MOST N records are processed at once
 * — a 10x burst manifests as Kafka consumer lag, FinnOne concurrency never
 * exceeds N, and the backlog drains to zero once the burst stops.
 *
 * <p>NOT a real FinnOne integration (that is Slice 4). It only meters.
 */
@Component
public class FinnOneBackpressureConsumer {

    private static final Logger log = LoggerFactory.getLogger(FinnOneBackpressureConsumer.class);

    private final FinnOneMeterPort finnOne;
    private final ObjectMapper objectMapper;

    public FinnOneBackpressureConsumer(FinnOneMeterPort finnOne, ObjectMapper objectMapper) {
        this.finnOne = finnOne;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "#{'${idfc.edge.finnone.consume-topics:orig.sfdc.pl.v1}'.split(',')}",
            groupId = "finnone-backpressure-harness",
            containerFactory = "boundedFinnOneListenerFactory")
    public void onMessage(String envelopeJson) {
        String applicationRef = readApplicationRef(envelopeJson);
        // Processed synchronously on a bounded set of listener threads (cap N):
        // this is what bounds FinnOne concurrency and pushes the backlog into Kafka.
        finnOne.invokeStoredProc(applicationRef);
    }

    private String readApplicationRef(String envelopeJson) {
        try {
            JsonNode node = objectMapper.readTree(envelopeJson);
            JsonNode ref = node.get("applicationRef");
            return ref == null || ref.isNull() ? "unknown" : ref.asText();
        } catch (Exception e) {
            log.warn("finnone.harness could not parse envelope; using 'unknown' applicationRef");
            return "unknown";
        }
    }
}
