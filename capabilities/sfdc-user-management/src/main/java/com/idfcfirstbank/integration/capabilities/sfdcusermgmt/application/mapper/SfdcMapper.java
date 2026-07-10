package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.mapper;

import java.util.Map;

/**
 * A one-way shape transform (request OR response). The DEFAULT is {@link #IDENTITY}
 * (raw-JSON passthrough) — we do NOT hand-roll per-svcName mappers pre-emptively; a
 * mapper pair is registered only where a svcName genuinely needs its shape transformed
 * or filtered, exactly as the verification capability's MapperRegistry does.
 */
@FunctionalInterface
public interface SfdcMapper {

    Map<String, Object> apply(Map<String, Object> in);

    /** Raw-JSON passthrough — the shape is sent/returned unchanged. */
    SfdcMapper IDENTITY = in -> in == null ? Map.of() : in;
}
