package com.idfcfirstbank.integration.capabilities.verification.application;

/** The (requestMapper, responseMapper) pair for one svcName (config-as-data, BRD B.0). */
public record MapperPair(Mapper request, Mapper response) {
    public static MapperPair passthrough() {
        return new MapperPair(Mapper.passthrough(), Mapper.passthrough());
    }
}
