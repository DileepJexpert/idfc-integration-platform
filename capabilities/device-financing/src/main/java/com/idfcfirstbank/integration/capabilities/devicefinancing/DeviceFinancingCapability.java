package com.idfcfirstbank.integration.capabilities.devicefinancing;

import com.idfcfirstbank.integration.shared.capability.Capability;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DEMO — the org-agnostic executor pattern from the legacy analysis, on the
 * platform's capability framework, now doing REAL outbound HTTP. Three
 * operations, ZERO brand branching: every per-brand difference (validate-or-not,
 * auth scheme, pass-logic field path) comes from {@link DeviceFinancingProperties}
 * config rows; the vendor call itself goes over the wire via
 * {@link DeviceFinancingVendorClient} and only the response DATA is mocked. An
 * unknown brand FAILS CLOSED (PERMANENT) — the legacy fail-open unknown-orgId is
 * deliberately not reproduced.
 */
@Component
public class DeviceFinancingCapability implements Capability {

    private final DeviceFinancingProperties props;
    private final DeviceFinancingVendor vendor;

    public DeviceFinancingCapability(DeviceFinancingProperties props,
                                         DeviceFinancingVendor vendor) {
        this.props = props;
        this.vendor = vendor;
    }

    @Override
    public String key() {
        return "device-financing";
    }

    @Override
    public List<CapabilityOperation> operations() {
        return List.of(
                op("resolveBrand", this::resolveBrand),
                op("validate", r -> vendorCheck("validate", r)),
                op("block", r -> vendorCheck("block", r)));
    }

    /** The journey's first hop: brand name -> its CONFIG ROW (ids/flags only). */
    private Map<String, Object> resolveBrand(CapabilityRequest request) {
        String brand = brandOf(request);
        DeviceFinancingProperties.BrandRow row = rowOf(brand);
        return Map.of(
                "brand", brand,
                "validationRequired", row.validationRequired(),
                "authType", row.authType());
    }

    /**
     * validate and block share ONE implementation on purpose — same
     * parameterized vendor call with a different operation. The REAL HTTP call
     * returns the vendor's shape; the row's passPath/passValue decide approved.
     */
    private Map<String, Object> vendorCheck(String operation, CapabilityRequest request) {
        String brand = brandOf(request);
        String deviceId = stringField(request, "deviceId");
        DeviceFinancingProperties.BrandRow row = rowOf(brand);

        Map<String, Object> vendorResponse = vendor.call(operation, brand, deviceId, row);
        boolean approved = String.valueOf(resolvePath(vendorResponse, row.passPath()))
                .equals(row.passValue());
        return Map.of(
                "brand", brand,
                "approved", approved,
                "authType", row.authType(),
                "vendor", vendorResponse);
    }

    // ---- helpers ---------------------------------------------------------------

    private String brandOf(CapabilityRequest request) {
        String brand = stringField(request, "brand");
        if (brand == null || brand.isBlank()) {
            throw new CapabilityException(ErrorClass.PERMANENT, "missing brand");
        }
        return brand;
    }

    private DeviceFinancingProperties.BrandRow rowOf(String brand) {
        DeviceFinancingProperties.BrandRow row = props.brands().get(brand);
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
