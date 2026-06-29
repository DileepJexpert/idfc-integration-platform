package com.idfcfirstbank.integration.capabilities.bureau.config;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Bureau configuration (config-as-data). {@code defaultBureauTypes} is which
 * bureaus a request pulls when it doesn't specify; each vendor has its own
 * mock/real mode + URL. Adding a bureau is a config row + an adapter, not a
 * new service.
 */
@ConfigurationProperties(prefix = "idfc.bureau")
public record BureauProperties(
        List<BureauType> defaultBureauTypes,
        Vendor cibil,
        Vendor multiBureau,
        Vendor commercial) {

    public BureauProperties {
        defaultBureauTypes = defaultBureauTypes == null || defaultBureauTypes.isEmpty()
                ? List.of(BureauType.CIBIL) : defaultBureauTypes;
        cibil = cibil == null ? new Vendor(null, null) : cibil;
        multiBureau = multiBureau == null ? new Vendor(null, null) : multiBureau;
        commercial = commercial == null ? new Vendor(null, null) : commercial;
    }

    /** A vendor binding: mock (local) or real (HTTP at {@code url}). */
    public record Vendor(String mode, String url) {
        public Vendor {
            mode = mode == null || mode.isBlank() ? "mock" : mode;
        }

        public boolean isReal() {
            return "real".equalsIgnoreCase(mode);
        }
    }
}
