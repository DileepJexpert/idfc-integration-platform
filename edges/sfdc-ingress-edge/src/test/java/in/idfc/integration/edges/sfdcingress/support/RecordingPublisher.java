package in.idfc.integration.edges.sfdcingress.support;

import in.idfc.integration.edges.sfdcingress.domain.model.CanonicalEnvelope;
import in.idfc.integration.edges.sfdcingress.domain.model.RoutingDecision;
import in.idfc.integration.edges.sfdcingress.domain.port.MessagePublisherPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Test publisher: records publishes/DLQs and can be set to fail publishes (transient). */
public class RecordingPublisher implements MessagePublisherPort {

    public final List<CanonicalEnvelope> published = new ArrayList<>();
    public final List<String> publishedTopics = new ArrayList<>();
    public final List<String> dlqReasons = new ArrayList<>();
    private volatile boolean failPublish;

    public void failPublishesWith(boolean fail) {
        this.failPublish = fail;
    }

    @Override
    public void publish(CanonicalEnvelope envelope, RoutingDecision routing, Map<String, String> headers) {
        if (failPublish) {
            throw new RuntimeException("simulated broker down");
        }
        published.add(envelope);
        publishedTopics.add(routing.topic());
    }

    @Override
    public void publishToDlq(CanonicalEnvelope envelope, Map<String, String> headers, String reason) {
        dlqReasons.add(reason);
    }
}
