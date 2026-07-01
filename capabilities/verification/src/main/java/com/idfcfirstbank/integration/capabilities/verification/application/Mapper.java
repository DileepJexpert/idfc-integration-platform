package com.idfcfirstbank.integration.capabilities.verification.application;

import java.util.Map;

/** A request or response mapper. A NA/null mapper is the identity (raw-JSON
 *  passthrough) — one of the two real wrapper behaviours we preserve. */
@FunctionalInterface
public interface Mapper {
    Map<String, Object> map(Map<String, Object> in);

    /** Raw-JSON passthrough — the NA/null-mapper behaviour. */
    static Mapper passthrough() {
        return in -> in == null ? Map.of() : in;
    }
}
