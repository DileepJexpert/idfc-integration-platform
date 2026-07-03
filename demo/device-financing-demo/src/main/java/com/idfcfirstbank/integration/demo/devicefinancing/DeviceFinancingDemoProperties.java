package com.idfcfirstbank.integration.demo.devicefinancing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * The demo's whole point, as a type: a BRAND IS A CONFIG ROW. Auth scheme,
 * whether validation runs at all, and WHERE the vendor's pass flag lives
 * (the legacy estate's per-brand "pass-logic field path") are all data here —
 * the capability code never mentions a brand name.
 *
 * <p>{@code declineDeviceIds}/{@code failDeviceIds} are demo levers: device ids
 * that make the mocked vendor return a non-pass response (business DECLINE) or
 * throw (technical FAILURE), so the ops view has all three outcomes to show.
 */
@ConfigurationProperties("demo.device-financing")
public record DeviceFinancingDemoProperties(
        Map<String, BrandRow> brands,
        List<String> declineDeviceIds,
        List<String> failDeviceIds) {

    public DeviceFinancingDemoProperties {
        brands = brands == null ? Map.of() : Map.copyOf(brands);
        declineDeviceIds = declineDeviceIds == null ? List.of() : List.copyOf(declineDeviceIds);
        failDeviceIds = failDeviceIds == null ? List.of() : List.copyOf(failDeviceIds);
    }

    /**
     * One legacy brand config file, reduced to a row: auth type (OAUTH / BAUTH /
     * NA — spelled as the legacy estate spells them), whether the validation
     * activity runs, the dotted path to the vendor's pass field, the value that
     * means "pass", and the mocked vendor's response shape.
     */
    public record BrandRow(
            String authType,
            boolean validationRequired,
            String passPath,
            String passValue,
            Map<String, Object> stubResponse) {

        public BrandRow {
            stubResponse = stubResponse == null ? Map.of() : Map.copyOf(stubResponse);
        }
    }
}
