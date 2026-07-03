rootProject.name = "idfc-integration-platform"

// ---------------------------------------------------------------------------
// Module map (kickoff §3). Slice 1 implements ONLY edges:sfdc-ingress-edge for
// real; every other module is an empty, runnable stub. Adding a module = one
// include line here + a build.gradle.kts that applies the shared convention.
// ---------------------------------------------------------------------------

// shared/ — cross-cutting libraries (no Spring Boot app of their own)
include("shared:shared-domain")        // canonical envelope + common value objects (NO framework imports)
include("shared:shared-observability") // otel/micrometer wiring helpers
include("shared:shared-capability")    // THE homogeneous capability framework (engine-invokable shell)

// platform/ — horizontal platform services (future homes for extracted code)
include("platform:platform-idempotency") // later extraction target for the Aerospike store
include("platform:platform-auth")         // Hydra + Kong two-token auth
include("platform:platform-config")       // org-config-as-data store
include("platform:platform-messaging")    // shared Kafka helpers
include("platform:route-config-registry") // API-router endpoint/gateway config registry (BRD §7)
include("platform:journey-registry")      // journey/version store + maker-checker (the designer↔engine seam)
include("platform:ops-query")             // read-only ops window over run state (Temporal-style ops view, B.3)

// integration/ — thin routing adapters (not capabilities)
include("integration:brand-router")       // SFDC brand routing Kafka|ActiveMQ (BRD §6)
include("integration:sfdc-response")       // consolidated per-org SFDC egress (verification spec v2 §B)

// edges/ — protocol edges (thin; no business logic)
include("edges:sfdc-ingress-edge")     // *** Slice 1 — IMPLEMENTED FOR REAL ***
include("edges:sfdc-egress-edge")      // stub (later slice)
include("edges:digital-partner-edge")  // digital twin: partner REST -> SAME envelope/topic/engine

// capabilities/ — business capabilities (stubs in Slice 1)
include("capabilities:kyc")
include("capabilities:bureau")
include("capabilities:scoring")
include("capabilities:lending-origination")
include("capabilities:lending-servicing")
include("capabilities:customer-party")
include("capabilities:payments")
include("capabilities:echo")           // trivial reference capability — proves the framework (BRD §9 step 1)
include("capabilities:mandate")        // e-mandate lifecycle — reference capability (BRD §3, step 2)
include("capabilities:communications") // SENDSMS/OTP notification action — consumes the SFDC edge's SENDSMS route
include("capabilities:verification")   // per-svcName verification (Karza/IMPS) — control-plane routed, DLQ-on-failure

// orchestration/ — long-running journeys (stubs in Slice 1)
include("orchestration:origination-journey")

// demo/ — DEMO SCAFFOLDING ONLY (legacy-patterns demo): mocked vendors, a
// local-folder file edge. Explicitly NOT the census-gated migration target
// (docs/legacy-analysis-review.md §6/§8) — never grow production code here.
include("demo:device-financing-demo")   // brand-as-config capability (real HTTP → WireMock)
include("demo:fusion-hcm-demo")         // Fusion capability (real HTTP → WireMock) + demo file edge

// full-flow demo integration test (no main code). Wires the engine + all five
// capability services to prove the end-to-end choreography (edge output ->
// engine -> capabilities -> branch -> decision) both ways.
include("full-flow-it")
