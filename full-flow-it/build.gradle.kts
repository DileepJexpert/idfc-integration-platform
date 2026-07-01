plugins {
    id("idfc.library-conventions")
}

// Test-only module: proves the full demo choreography end-to-end by wiring the
// REAL engine and the REAL capability services together over an in-memory bus
// (no Kafka, no Docker) — exercising the real DecisionRule, bureau scoring and
// booking. The Testcontainers/compose-based live run is the demo script.
description = "full-flow demo integration test"

dependencies {
    testImplementation(project(":shared:shared-domain"))
    testImplementation(project(":shared:shared-capability"))
    testImplementation(project(":orchestration:origination-journey"))
    testImplementation(project(":capabilities:customer-party"))
    testImplementation(project(":capabilities:kyc"))
    testImplementation(project(":capabilities:bureau"))
    testImplementation(project(":capabilities:scoring"))
    testImplementation(project(":capabilities:lending-origination"))
    testImplementation(project(":capabilities:mandate"))
    testImplementation(project(":capabilities:communications"))
    testImplementation(project(":capabilities:verification"))
    testImplementation(project(":edges:sfdc-ingress-edge"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
}
