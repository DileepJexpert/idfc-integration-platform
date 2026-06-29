package com.idfcfirstbank.integration.digitaledge.config;

import com.idfcfirstbank.integration.digitaledge.config.DigitalEdgeProperties.Partner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a partner from its inbound auth token (config-as-data). A partner is a
 * config row — adding one needs no code. The body never carries the partner; it
 * is derived from auth so it can't be spoofed.
 */
public class PartnerRegistry {

    private final Map<String, Partner> byToken = new LinkedHashMap<>();
    private final Map<String, Partner> byCode = new LinkedHashMap<>();

    public PartnerRegistry(DigitalEdgeProperties properties) {
        for (Partner p : properties.partners()) {
            if (p.token() != null) {
                byToken.put(p.token(), p);
            }
            byCode.put(p.code(), p);
        }
    }

    public Optional<Partner> resolveByToken(String token) {
        return token == null ? Optional.empty() : Optional.ofNullable(byToken.get(token));
    }

    public Optional<Partner> byCode(String code) {
        return Optional.ofNullable(byCode.get(code));
    }
}
