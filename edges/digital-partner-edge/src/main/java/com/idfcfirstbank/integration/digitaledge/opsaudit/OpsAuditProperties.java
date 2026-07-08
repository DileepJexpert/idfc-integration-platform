package com.idfcfirstbank.integration.digitaledge.opsaudit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth config for the edge's audited {@code /ops} surface. FAIL CLOSED: the base
 * profile supplies NO token, so a blank {@code auth-token} authorizes nothing — the
 * local/compose profile opts in with a dev value; prod injects the real secret. The
 * same {@code X-Ops-Token} the journey ops view uses (one ops token, both surfaces).
 */
@ConfigurationProperties(prefix = "idfc.ops")
public record OpsAuditProperties(String authToken) {
}
