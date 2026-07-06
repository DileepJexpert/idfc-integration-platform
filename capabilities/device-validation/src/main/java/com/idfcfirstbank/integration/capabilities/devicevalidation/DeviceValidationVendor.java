package com.idfcfirstbank.integration.capabilities.devicevalidation;

import com.idfcfirstbank.integration.capabilities.devicevalidation.DeviceValidationProperties.BrandRow;

import java.util.Map;

/**
 * The vendor call as the capability sees it — a seam so the capability's
 * per-brand LOGIC (pass-path, fail-closed, decline) is unit-testable without a
 * socket, while the real implementation ({@link DeviceValidationVendorClient})
 * does actual HTTP. The demo's "real flow" is proven end-to-end in
 * {@code LegacyPatternsDemoIT} against the real mock-vendors server.
 */
public interface DeviceValidationVendor {

    /** Call the vendor over HTTP; return its (mocked-data) response shape. */
    Map<String, Object> call(String operation, String brand, String deviceId, BrandRow row);
}
