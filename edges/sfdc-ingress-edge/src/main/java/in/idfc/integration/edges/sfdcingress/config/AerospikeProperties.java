package in.idfc.integration.edges.sfdcingress.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Aerospike connection + idempotency-set config. The TTL default (30 days) is a
 * placeholder that MUST exceed SFDC's confirmed max retry/redelivery window
 * (punch list §D / A1) — a record expiring before SFDC can still resend would be
 * a silent double-book. No endpoint values are hardcoded; they come from config.
 */
@ConfigurationProperties(prefix = "idfc.aerospike")
public record AerospikeProperties(
        String host,
        int port,
        String namespace,
        String recordSet,
        String appPointerSet,
        int ttlSeconds) {

    public AerospikeProperties {
        host = host == null ? "localhost" : host;
        port = port <= 0 ? 3000 : port;
        namespace = namespace == null ? "idfc" : namespace;
        recordSet = recordSet == null ? "idem" : recordSet;
        appPointerSet = appPointerSet == null ? "idem_app" : appPointerSet;
        ttlSeconds = ttlSeconds <= 0 ? 30 * 24 * 60 * 60 : ttlSeconds; // 30 days default
    }
}
