package com.idfcfirstbank.integration.edges.sfdcingress.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compares an edge outcome against a recorded Mule fixture (punch list §F).
 * Parity = resolved-payload byte-equality + routing decision + dedup verdict +
 * identity fields all match. Differences in allowlisted fields cannot occur
 * because {@link ParitySnapshot} excludes them by construction; anything that
 * DOES differ here is a real parity bug and blocks cutover.
 */
public class ParityOracle {

    public ParityResult compare(ParitySnapshot expected, ParitySnapshot actual) {
        List<String> diffs = new ArrayList<>();
        diff(diffs, "dedupVerdict", expected.dedupVerdict(), actual.dedupVerdict());
        diff(diffs, "notificationId", expected.notificationId(), actual.notificationId());
        diff(diffs, "orgId", expected.orgId(), actual.orgId());
        diff(diffs, "type", expected.type(), actual.type());
        diff(diffs, "sfdcRecordId", expected.sfdcRecordId(), actual.sfdcRecordId());
        diff(diffs, "applicationRef", expected.applicationRef(), actual.applicationRef());
        diff(diffs, "routingTopic", expected.routingTopic(), actual.routingTopic());
        diff(diffs, "downstreamJourney", expected.downstreamJourney(), actual.downstreamJourney());
        if (!expected.payloadEquals(actual)) {
            diffs.add("resolvedPayload: bytes differ (fetched s3Ref content not byte-equal to Mule body)");
        }
        return ParityResult.of(diffs);
    }

    private static void diff(List<String> diffs, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            diffs.add(field + ": expected=" + expected + " actual=" + actual);
        }
    }
}
