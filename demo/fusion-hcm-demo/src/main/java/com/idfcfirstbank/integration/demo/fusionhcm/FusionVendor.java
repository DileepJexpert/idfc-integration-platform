package com.idfcfirstbank.integration.demo.fusionhcm;

import java.util.Map;

/**
 * Fusion HCM as the capability sees it — a seam so the per-record LOGIC is
 * unit-testable without a socket, while {@link FusionVendorClient} does real
 * HTTP. The "real flow" is proven end-to-end in LegacyPatternsDemoIT against
 * the mock-vendors server.
 */
public interface FusionVendor {

    /** POST an employee update over HTTP; return the (mocked-data) response. */
    Map<String, Object> updateEmployee(String employeeId, String lastWorkingDay);

    /** GET an employee over HTTP (for the sync-read reference drawing). */
    Map<String, Object> getEmployee(String employeeId);
}
