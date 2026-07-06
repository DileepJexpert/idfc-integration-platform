package com.idfcfirstbank.integration.capabilities.impsdisbursal.adapter.in.rest;

/**
 * The UNIFORM error body returned to the caller when a sync call cannot produce a
 * business result — auth rejected, request invalid, or a downstream technical
 * failure. Never a fake success. {@code errorClass} (PERMANENT/TRANSIENT/AMBIGUOUS)
 * lets the caller tell a definitely-not-done apart from a maybe-done (e.g. a read
 * timeout on a money movement).
 */
public record SyncErrorResponse(String status, String code, String errorClass, String message) {

    public static SyncErrorResponse of(String code, String errorClass, String message) {
        return new SyncErrorResponse("ERROR", code, errorClass, message);
    }
}
