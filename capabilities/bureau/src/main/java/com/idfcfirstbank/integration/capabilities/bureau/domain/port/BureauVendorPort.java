package com.idfcfirstbank.integration.capabilities.bureau.domain.port;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.CanonicalBureauResult;

import java.util.Map;

/**
 * OUT port to a single bureau vendor. The adapter translates the vendor's wire
 * shape into the {@link CanonicalBureauResult} — callers never see vendor shapes.
 * There is one port per vendor (CIBIL / Multi-Bureau / Commercial / scorecard
 * infra) so each is independently ownable, swappable, and securable.
 */
public interface BureauVendorPort {

    /** Which bureau this port pulls (lets the fan-out index ports by type). */
    BureauType type();

    /** Pull + normalize this vendor's report for the applicant identity. */
    CanonicalBureauResult fetch(Map<String, Object> identity);
}
