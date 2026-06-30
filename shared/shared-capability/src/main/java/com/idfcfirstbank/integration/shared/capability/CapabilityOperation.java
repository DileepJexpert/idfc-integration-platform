package com.idfcfirstbank.integration.shared.capability;

import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;

import java.util.Map;

/**
 * One named operation of a capability (BRD §2 {@code operation}). Returns the
 * output map that binds into the journey context. Throw {@link CapabilityException}
 * to fail with a classification; any other exception is treated as PERMANENT.
 */
public interface CapabilityOperation {

    String name();

    Map<String, Object> execute(CapabilityRequest request) throws Exception;
}
