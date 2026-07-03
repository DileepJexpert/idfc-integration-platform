package com.idfcfirstbank.integration.demo.devicefinancing;

import com.idfcfirstbank.integration.shared.capability.Capability;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEMO — the org-agnostic executor pattern from the legacy analysis, on the
 * platform's capability framework. Three operations, ZERO brand branching:
 * every per-brand difference (validate-or-not, auth scheme, pass-logic field
 * path) comes from {@link DeviceFinancingDemoProperties} config rows. An
 * unknown brand FAILS CLOSED (PERMANENT) — the legacy estate's fail-open
 * unknown-orgId behaviour is deliberately not reproduced.
 *
 * <p>The "vendor" is the config row's stubbed response shape — this module
 * demonstrates config-selects-behaviour, not real vendor integration.
 */
@Component
public class DeviceFinancingDemoCapability implements Capability {

    private final DeviceFinancingDemoProperties props;

    public DeviceFinancingDemoCapability(DeviceFinancingDemoProperties props) {
        this.props = props;
    }

    @Override
    public String key() {
        return "device-financing";
    }

    @Override
    public List<CapabilityOperation> operations() {
        return List.of(
                op("resolveBrand", this::resolveBrand),
                op("validate", this::vendorCheck),
                op("block", this::vendorCheck));
    }

    /** The journey's first hop: brand name -> its CONFIG ROW (ids/flags only). */
    private Map<String, Object> resolveBrand(CapabilityRequest request) {
        String brand = brandOf(request);
        DeviceFinancingDemoProperties.BrandRow row = rowOf(brand);
        return Map.of(
                "brand", brand,
                "validationRequired", row.validationRequired(),
                "authType", row.authType());
    }

    /**
     * validate and block share ONE implementation on purpose — in the legacy
     * estate they were the same parameterized vendor call with a different
     * activity flag. The mocked vendor answers with the row's stub shape; the
     * row's passPath/passValue decide approved.
     */
    private Map<String, Object> vendorCheck(CapabilityRequest request) {
        String brand = brandOf(request);
        String deviceId = stringField(request, "deviceId");
        DeviceFinancingDemoProperties.BrandRow row = rowOf(brand);

        if (props.failDeviceIds().contains(deviceId)) {
            // Demo lever: a technical vendor failure -> classified PERMANENT ->
            // the run FAILS and triage sees it (class name only on the wire).
            throw new CapabilityException(ErrorClass.PERMANENT,
                    "demo vendor rejected deviceId=" + deviceId);
        }

        Map<String, Object> vendorResponse = props.declineDeviceIds().contains(deviceId)
                ? responseAt(row.passPath(), "DECLINED")
                : row.stubResponse();
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

    private DeviceFinancingDemoProperties.BrandRow rowOf(String brand) {
        DeviceFinancingDemoProperties.BrandRow row = props.brands().get(brand);
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

    /** Build a nested map carrying {@code value} at the dotted path (decline shape). */
    private static Map<String, Object> responseAt(String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            current.put(segments[i].trim(), child);
            current = child;
        }
        current.put(segments[segments.length - 1].trim(), value);
        return root;
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
