package com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out;

import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.model.ClosureRecord;

/** Closure state store. {@code insertIfAbsent} dedups on LAN+event so a redelivered
 * event does not create a duplicate SFDC case (BRD §4). In-memory default. */
public interface ClosureStorePort {
    boolean insertIfAbsent(ClosureRecord record);
    void save(ClosureRecord record);
}
