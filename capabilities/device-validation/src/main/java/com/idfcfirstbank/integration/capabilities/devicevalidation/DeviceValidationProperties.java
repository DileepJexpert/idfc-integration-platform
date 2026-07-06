package com.idfcfirstbank.integration.capabilities.devicevalidation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * A BRAND IS A CONFIG ROW. Device VALIDATION runs up to three activities against
 * a brand's vendor — <b>validate</b> (is this a real/eligible device?),
 * <b>block</b> (lock it under finance), <b>unblock</b> (release it) — over REAL
 * HTTP (only the vendor's response DATA is mocked, on the mock-vendors server).
 *
 * <p>Two independent gates decide whether an activity runs:
 * <ol>
 *   <li>the REQUEST asks for it — the {@code status} field maps to a set of
 *       activities via {@link #statusActivities} (e.g. status "1" = validate+block
 *       on disbursal, "2" = unblock on closure); and</li>
 *   <li>the BRAND supports it — the row's {@code validate}/{@code block}/
 *       {@code unblock} flags.</li>
 * </ol>
 * An activity runs only on the INTERSECTION (requested AND supported). A row also
 * says HOW to identify the device ({@code validateBy}: {@code imei} or
 * {@code serial}), the auth SCHEME, and WHERE the vendor's pass flag lives (the
 * per-brand pass-logic field path). Adding a brand is adding a row.
 */
@ConfigurationProperties("device-validation")
public record DeviceValidationProperties(
        String vendorBaseUrl,
        String tokenUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        String statusField,
        String defaultStatus,
        Map<String, List<String>> statusActivities,
        Map<String, BrandRow> brands) {

    /** The three activities this capability can run, in pipeline order. */
    public static final String VALIDATE = "validate";
    public static final String BLOCK = "block";
    public static final String UNBLOCK = "unblock";

    public DeviceValidationProperties {
        vendorBaseUrl = blankToNull(vendorBaseUrl);
        tokenUrl = blankToNull(tokenUrl);
        connectTimeoutMs = connectTimeoutMs <= 0 ? 3_000 : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs <= 0 ? 10_000 : readTimeoutMs;
        statusField = (statusField == null || statusField.isBlank()) ? "status" : statusField;
        defaultStatus = (defaultStatus == null || defaultStatus.isBlank()) ? "1" : defaultStatus;
        // The SFDC status semantics as DATA, not code: "1" (disbursal) asks to
        // validate then block; "2" (closure) asks to unblock. Overridable in yml.
        statusActivities = (statusActivities == null || statusActivities.isEmpty())
                ? Map.of("1", List.of(VALIDATE, BLOCK), "2", List.of(UNBLOCK))
                : Map.copyOf(statusActivities);
        brands = brands == null ? Map.of() : Map.copyOf(brands);
    }

    /** The activities the request is ASKING for, from its status value (never null). */
    public List<String> requestedActivities(String status) {
        String s = (status == null || status.isBlank()) ? defaultStatus : status;
        List<String> requested = statusActivities.get(s);
        return requested == null ? List.of() : requested;
    }

    /**
     * Resolve the brand-row KEY for an SFDC svcName. The REAL door carries brand
     * implicitly in the svcName (no brand field in the payload); each brand row
     * that has a real SFDC front door declares its {@code svcName}. Carried as a
     * FIELD on the all-uppercase-keyed brands map — which binds cleanly — rather
     * than a separate mixed-case-keyed collection that Spring relaxed binding
     * fails to bind.
     */
    public String brandForSvcName(String svcName) {
        if (svcName == null) {
            return null;
        }
        for (Map.Entry<String, BrandRow> e : brands.entrySet()) {
            if (svcName.equals(e.getValue().svcName())) {
                return e.getKey();
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * One brand config row. The three enabled-flags ({@code validate},
     * {@code block}, {@code unblock}) say which activities this brand SUPPORTS;
     * {@code validateBy} says whether the device is identified by {@code imei} or
     * {@code serial}. The rest is the vendor contract: auth SCHEME (+ credentials/
     * scope), the dotted path to the vendor's pass field, the value that means
     * "valid", and — for a brand with a real SFDC front door — the {@code svcName}
     * that maps to it.
     */
    public record BrandRow(
            boolean validate,
            boolean block,
            boolean unblock,
            String validateBy,
            String authType,
            String passPath,
            String passValue,
            String basicUser,
            String basicPassword,
            String scope,
            String svcName) {

        public BrandRow {
            validateBy = (validateBy == null || validateBy.isBlank()) ? "imei" : validateBy;
        }

        /** True if this brand supports the named activity. */
        public boolean supports(String activity) {
            return switch (activity) {
                case VALIDATE -> validate;
                case BLOCK -> block;
                case UNBLOCK -> unblock;
                default -> false;
            };
        }
    }
}
