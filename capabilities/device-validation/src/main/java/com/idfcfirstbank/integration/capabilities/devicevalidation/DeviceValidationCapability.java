package com.idfcfirstbank.integration.capabilities.devicevalidation;

import com.idfcfirstbank.integration.capabilities.devicevalidation.DeviceValidationProperties.BrandRow;
import com.idfcfirstbank.integration.shared.capability.Capability;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.idfcfirstbank.integration.capabilities.devicevalidation.DeviceValidationProperties.BLOCK;
import static com.idfcfirstbank.integration.capabilities.devicevalidation.DeviceValidationProperties.UNBLOCK;
import static com.idfcfirstbank.integration.capabilities.devicevalidation.DeviceValidationProperties.VALIDATE;

/**
 * Device VALIDATION as the org-agnostic executor pattern: ONE capability, FOUR
 * operations, ZERO brand branching. Every per-brand difference — which of the
 * three activities the brand supports, how the device is identified
 * ({@code imei}/{@code serial}), the auth scheme, the pass-logic field path —
 * comes from {@link DeviceValidationProperties} config rows; the vendor call goes
 * over the wire via {@link DeviceValidationVendorClient} and only the response
 * DATA is mocked.
 *
 * <p>{@code decideActivities} intersects what the REQUEST asks for (its status
 * field) with what the BRAND supports (its flags); the journey then runs
 * validate → block → unblock, each gated on that decision. An unknown brand or an
 * unmapped svcName FAILS CLOSED (PERMANENT) — the legacy fail-open unknown-orgId
 * is deliberately not reproduced.
 */
@Component
public class DeviceValidationCapability implements Capability {

    private final DeviceValidationProperties props;
    private final DeviceValidationVendor vendor;

    public DeviceValidationCapability(DeviceValidationProperties props,
                                      DeviceValidationVendor vendor) {
        this.props = props;
        this.vendor = vendor;
    }

    @Override
    public String key() {
        return "device-validation";
    }

    @Override
    public List<CapabilityOperation> operations() {
        return List.of(
                op("decideActivities", this::decideActivities),
                op(VALIDATE, r -> vendorCheck(VALIDATE, r)),
                op(BLOCK, r -> vendorCheck(BLOCK, r)),
                op(UNBLOCK, r -> vendorCheck(UNBLOCK, r)));
    }

    /**
     * The journey's first hop: decide which activities to run. An activity runs
     * only if the REQUEST asks for it (its status field → a set of activities) AND
     * the BRAND supports it (its flag). The gates are independent — the journey
     * reads {@code runValidate}/{@code runBlock}/{@code runUnblock} and skips the
     * hops that are off.
     */
    private Map<String, Object> decideActivities(CapabilityRequest request) {
        String brand = brandOf(request);
        BrandRow row = rowOf(brand);
        List<String> requested = props.requestedActivities(statusOf(request));
        return Map.of(
                "brand", brand,
                "validateBy", row.validateBy(),
                "authType", row.authType() == null ? "NA" : row.authType(),
                "runValidate", requested.contains(VALIDATE) && row.supports(VALIDATE),
                "runBlock", requested.contains(BLOCK) && row.supports(BLOCK),
                "runUnblock", requested.contains(UNBLOCK) && row.supports(UNBLOCK));
    }

    /**
     * validate / block / unblock share ONE implementation on purpose — the same
     * parameterized vendor call with a different operation. The REAL HTTP call
     * returns the vendor's shape; the row's passPath/passValue decide {@code valid}.
     */
    private Map<String, Object> vendorCheck(String operation, CapabilityRequest request) {
        String brand = brandOf(request);
        BrandRow row = rowOf(brand);
        String deviceId = deviceIdOf(request, row);

        Map<String, Object> vendorResponse = vendor.call(operation, brand, deviceId, row);
        boolean valid = String.valueOf(resolvePath(vendorResponse, row.passPath()))
                .equals(row.passValue());
        return Map.of(
                "brand", brand,
                "valid", valid,
                "authType", row.authType() == null ? "NA" : row.authType(),
                "vendor", vendorResponse);
    }

    // ---- helpers ---------------------------------------------------------------

    /**
     * Brand selector. A Kafka door may carry {@code brand} in the payload; the
     * REAL SFDC door does NOT — brand is implicit in the svcName (the envelope
     * {@code type}, e.g. {@code Post_Disbursal_Apple}), so fall back to the
     * configured svcName→brand map. Fails closed (PERMANENT) if neither yields a
     * brand — an unmapped svcName never silently runs.
     */
    private String brandOf(CapabilityRequest request) {
        String brand = stringField(request, "brand");
        if (brand == null || brand.isBlank()) {
            brand = props.brandForSvcName(stringField(request, "type"));
        }
        if (brand == null || brand.isBlank()) {
            throw new CapabilityException(ErrorClass.PERMANENT, "missing brand");
        }
        return brand;
    }

    /**
     * Device identifier for the vendor call, per the brand's {@code validateBy}:
     * an {@code imei}-brand reads {@code imei}, a {@code serial}-brand reads
     * {@code serial}. A Kafka door that sends a plain {@code deviceId} is the
     * fallback for either.
     */
    private static String deviceIdOf(CapabilityRequest request, BrandRow row) {
        String primary = "serial".equalsIgnoreCase(row.validateBy())
                ? stringField(request, "serial")
                : stringField(request, "imei");
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return stringField(request, "deviceId");
    }

    /** The request's activity-intent status (e.g. "1"/"2"); null → the configured default. */
    private String statusOf(CapabilityRequest request) {
        return stringField(request, props.statusField());
    }

    private BrandRow rowOf(String brand) {
        BrandRow row = props.brands().get(brand);
        if (row == null) {
            // FAIL CLOSED — the counter-design to the legacy fail-open orgId.
            throw new CapabilityException(ErrorClass.PERMANENT,
                    "no config row for brand=" + brand + " (fail closed)");
        }
        return row;
    }

    private static String stringField(CapabilityRequest request, String key) {
        Object v = request.payload() == null ? null : request.payload().get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** Walk a dotted path through nested maps (the per-brand pass-logic path). */
    private static Object resolvePath(Map<String, Object> root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = m.get(segment.trim());
        }
        return current;
    }

    private static CapabilityOperation op(String name, Operation fn) {
        return new CapabilityOperation() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Map<String, Object> execute(CapabilityRequest request) {
                return fn.apply(request);
            }
        };
    }

    @FunctionalInterface
    private interface Operation {
        Map<String, Object> apply(CapabilityRequest request);
    }
}
