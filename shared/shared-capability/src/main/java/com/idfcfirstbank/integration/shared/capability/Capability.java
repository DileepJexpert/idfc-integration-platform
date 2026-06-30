package com.idfcfirstbank.integration.shared.capability;

import java.util.List;

/**
 * A capability = a key (its module name, e.g. "mandate") + the operations it
 * exposes. A capability app provides exactly one {@code Capability} bean; the
 * framework wires the Kafka shell and idempotent dispatch around it.
 */
public interface Capability {

    String key();

    List<CapabilityOperation> operations();
}
