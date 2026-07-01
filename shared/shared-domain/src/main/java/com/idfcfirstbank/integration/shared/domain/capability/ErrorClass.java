package com.idfcfirstbank.integration.shared.domain.capability;

/**
 * Classifies a TRANSPORT/HTTP-level capability failure so the retry-policy engine can
 * decide whether to re-attempt (verification spec v2 §C). Business declines are NOT
 * here — they arrive as HTTP 200 with flags in the body and are handled by the
 * mapper/branch, never the classifier.
 *
 * <ul>
 *   <li>{@code TRANSIENT} — timeout to connect, connection refused, 503, network blip:
 *       safe to retry (exponential backoff + jitter).</li>
 *   <li>{@code PERMANENT} — 400/422 malformed, 401/403 auth: retrying is pointless →
 *       straight to DLQ + notify.</li>
 *   <li>{@code AMBIGUOUS} — failure AFTER the request may already have been processed
 *       (read timeout post-send): retry ONLY if the operation is {@code idempotent},
 *       else DLQ (never blind-retry a possible write).</li>
 * </ul>
 *
 * Carried on {@link CapabilityResponse}; the retry-policy engine acts on it.
 */
public enum ErrorClass {
    TRANSIENT,
    PERMANENT,
    AMBIGUOUS
}
