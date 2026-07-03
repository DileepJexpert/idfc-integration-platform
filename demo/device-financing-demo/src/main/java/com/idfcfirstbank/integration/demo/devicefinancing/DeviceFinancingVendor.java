package com.idfcfirstbank.integration.demo.devicefinancing;

import com.idfcfirstbank.integration.demo.devicefinancing.DeviceFinancingDemoProperties.BrandRow;

import java.util.Map;

/**
 * The vendor call as the capability sees it — a seam so the capability's
 * per-brand LOGIC (pass-path, fail-closed, decline) is unit-testable without a
 * socket, while the real implementation ({@link DeviceFinancingVendorClient})
 * does actual HTTP. The demo's "real flow" is proven end-to-end in
 * {@code LegacyPatternsDemoIT} against the real mock-vendors server.
 */
public interface DeviceFinancingVendor {

    /** Call the vendor over HTTP; return its (mocked-data) response shape. */
    Map<String, Object> call(String operation, String brand, String deviceId, BrandRow row);
}
