package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.mapper;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * svcName -&gt; {@link SfdcMapperPair} (config-as-data). An unregistered svcName falls
 * back to raw-JSON passthrough — mirrors the verification capability's MapperRegistry.
 * Slice 1 registers NOTHING (every read is passthrough); a real mapper pair is added
 * only when a confirmed SFDC payload needs transforming.
 */
@Component
public class SfdcMapperRegistry {

    private final Map<String, SfdcMapperPair> bySvcName = new ConcurrentHashMap<>();

    /** Register a svcName's mapper pair (called by a future adapter slice's config). */
    public void register(String svcName, SfdcMapperPair pair) {
        bySvcName.put(svcName, pair);
    }

    /** The pair for a svcName; unregistered -&gt; passthrough (raw JSON). */
    public SfdcMapperPair forSvcName(String svcName) {
        return bySvcName.getOrDefault(svcName, SfdcMapperPair.passthrough());
    }
}
