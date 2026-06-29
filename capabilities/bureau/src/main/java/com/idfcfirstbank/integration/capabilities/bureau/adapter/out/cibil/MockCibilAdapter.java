package com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauReport;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CibilPort;

import java.util.Map;

/**
 * Local mock CIBIL — deterministic report derived from the applicant's PAN, so
 * the bureau branch is demoable BOTH ways without a docker vendor. Used for unit
 * tests and when {@code idfc.bureau.cibil.mode=mock}.
 *
 * <p>The real high/low fixtures come from the docker mock vendor (compose); this
 * heuristic just mirrors them: a "LOW" marker in either the PAN or the
 * applicationRef yields a low/declinable profile, anything else a high/clean one.
 * applicationRef is used too because in the LIVE edge path the inline PAN travels
 * via the S3 claim-check, whereas applicationRef is always on the envelope.
 */
public class MockCibilAdapter implements CibilPort {

    @Override
    public BureauReport fetch(Map<String, Object> identity) {
        String pan = String.valueOf(identity.getOrDefault("pan", "UNKNOWN"));
        String applicationRef = String.valueOf(identity.getOrDefault("applicationRef", ""));
        String marker = (pan + " " + applicationRef).toUpperCase();
        if (marker.contains("LOW")) {
            return new BureauReport(540, "C", "CIBIL-" + pan);
        }
        return new BureauReport(780, "A", "CIBIL-" + pan);
    }
}
