package com.idfcfirstbank.integration.digitaledge.support;

import com.idfcfirstbank.integration.digitaledge.domain.port.EnvelopePublisherPort;
import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;

import java.util.ArrayList;
import java.util.List;

/** Test publisher: records published envelopes + topics; can simulate a transient failure. */
public class RecordingEnvelopePublisher implements EnvelopePublisherPort {

    public final List<CanonicalEnvelope> published = new ArrayList<>();
    public final List<String> topics = new ArrayList<>();
    private boolean failPublish;

    public void failPublishesWith(boolean fail) {
        this.failPublish = fail;
    }

    @Override
    public void publish(CanonicalEnvelope envelope, String topic) {
        if (failPublish) {
            throw new RuntimeException("simulated broker down");
        }
        published.add(envelope);
        topics.add(topic);
    }
}
