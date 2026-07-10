package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.mapper;

/**
 * The request+response mapper pair for one svcName. {@link #passthrough()} is the
 * default: send the operation payload as-is, return the SFDC body as-is. Real SFDC
 * schemas are large and per-svcName; until confirmed payloads arrive, passthrough is
 * the honest default and specific pairs are registered incrementally.
 */
public record SfdcMapperPair(SfdcMapper request, SfdcMapper response) {

    public static SfdcMapperPair passthrough() {
        return new SfdcMapperPair(SfdcMapper.IDENTITY, SfdcMapper.IDENTITY);
    }
}
