package com.idfcfirstbank.integration.digitaledge.opsaudit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Auth + CORS config for the edge's audited {@code /ops} surface. FAIL CLOSED: the base
 * profile supplies NO token, so a blank {@code auth-token} authorizes nothing — the
 * local/compose profile opts in with a dev value; prod injects the real secret. The
 * same {@code X-Ops-Token} the journey ops view uses (one ops token, both surfaces).
 *
 * <p>{@code corsAllowedOriginPatterns}: the sync-invocations view is a browser app, so
 * the surface must emit CORS headers or the cross-origin read is blocked (curl still
 * works — it does no CORS). Defaults to any localhost port for dev; prod narrows it via
 * {@code idfc.ops.cors-allowed-origin-patterns}. The token stays lenient here (no
 * throw-on-blank) so the edge still boots in the base profile — the filter fails closed
 * at request time.
 */
@ConfigurationProperties(prefix = "idfc.ops")
public record OpsAuditProperties(String authToken, List<String> corsAllowedOriginPatterns) {

    public OpsAuditProperties {
        if (corsAllowedOriginPatterns == null || corsAllowedOriginPatterns.isEmpty()) {
            corsAllowedOriginPatterns = List.of("http://localhost:[*]");
        }
    }
}
