package com.idfcfirstbank.integration.edges.sfdcingress.domain.model;

/**
 * The business line ("type") an SFDC event routes on. These codes are
 * PLACEHOLDERS pending confirmation from the SFDC payload owner (punch list §E).
 * The set is intentionally NOT used for routing decisions directly — routing
 * lives in org-config-as-data ({@code OrgConfigPort}); this enum only models the
 * known vocabulary so callers stay type-safe. Adding a line is a config row.
 */
public enum BusinessLine {
    PERSONAL_LOAN,
    LAP,
    BUSINESS_LOAN,
    COMMERCIAL;

    /** Lenient parse: unknown codes return null so the caller can route to DLQ. */
    public static BusinessLine fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        try {
            return BusinessLine.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
