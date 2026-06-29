plugins {
    id("idfc.spring-boot-app-conventions")
}

// *** Slice 2 — the first CAPABILITY; the template every capability reuses. ***
// Hexagonal, like the edge: framework-free domain + IN/OUT ports + service
// (the superset of absorbed bureau-fetch logic) + adapters (one per vendor,
// mock[local]/real[prod]). Stateless data-fetch — NO scoring/decisioning/KYC.
// See docs/BUREAU_CAPABILITY_BUILD_DOC.md (authoritative).
description = "Bureau capability — canonical credit-bureau fetch (Slice 2)"

// Steps 1-2 (module + domain + ports) need no extra dependencies beyond the
// shared app convention. Vendor HTTP clients, Resilience4j, and the optional
// Aerospike cache arrive with the adapters/cache steps (3-7), post-review.
