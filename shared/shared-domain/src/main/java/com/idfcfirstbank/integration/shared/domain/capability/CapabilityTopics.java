package com.idfcfirstbank.integration.shared.domain.capability;

/**
 * THE CAPABILITY CONTRACT (topic naming). The engine and every capability derive
 * topic names from the capability key with this single convention — never
 * hand-spell a topic string. Drift here is exactly the integration-hell this
 * shared type prevents.
 *
 * <ul>
 *   <li>request  topic = {@code cap.<capabilityKey>.request.v1}</li>
 *   <li>response topic = {@code cap.<capabilityKey>.response.v1}</li>
 * </ul>
 */
public final class CapabilityTopics {

    public static final String REQUEST_SUFFIX = ".request.v1";
    public static final String RESPONSE_SUFFIX = ".response.v1";
    private static final String PREFIX = "cap.";

    private CapabilityTopics() {
    }

    public static String request(String capabilityKey) {
        return PREFIX + capabilityKey + REQUEST_SUFFIX;
    }

    public static String response(String capabilityKey) {
        return PREFIX + capabilityKey + RESPONSE_SUFFIX;
    }
}
