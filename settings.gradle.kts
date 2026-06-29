rootProject.name = "idfc-integration-platform"

// ---------------------------------------------------------------------------
// Module map (kickoff §3). Slice 1 implements ONLY edges:sfdc-ingress-edge for
// real; every other module is an empty, runnable stub. Adding a module = one
// include line here + a build.gradle.kts that applies the shared convention.
// ---------------------------------------------------------------------------

// shared/ — cross-cutting libraries (no Spring Boot app of their own)
include("shared:shared-domain")        // canonical envelope + common value objects (NO framework imports)
include("shared:shared-observability") // otel/micrometer wiring helpers

// platform/ — horizontal platform services (future homes for extracted code)
include("platform:platform-idempotency") // later extraction target for the Aerospike store
include("platform:platform-auth")         // Hydra + Kong two-token auth
include("platform:platform-config")       // org-config-as-data store
include("platform:platform-messaging")    // shared Kafka helpers

// edges/ — protocol edges (thin; no business logic)
include("edges:sfdc-ingress-edge")     // *** Slice 1 — IMPLEMENTED FOR REAL ***
include("edges:sfdc-egress-edge")      // stub (later slice)

// capabilities/ — business capabilities (stubs in Slice 1)
include("capabilities:kyc")
include("capabilities:bureau-capability")
include("capabilities:scoring")
include("capabilities:lending-origination")
include("capabilities:lending-servicing")
include("capabilities:customer-party")
include("capabilities:payments")

// orchestration/ — long-running journeys (stubs in Slice 1)
include("orchestration:origination-journey")
