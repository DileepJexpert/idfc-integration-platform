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
    testImplementation(project(":edges:digital-partner-edge"))
    // LegacyPatternsDemoIT — the promoted capabilities + the file-batch ingress edge.
    testImplementation(project(":capabilities:device-validation"))
    testImplementation(project(":capabilities:fusion-hcm"))
    testImplementation(project(":edges:file-batch-edge"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310") // Instant on the envelope

    // RegistryEngineSeamIT: the REAL engine app booted against the REAL
    // journey-registry over HTTP, with an embedded Kafka carrying the traffic.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.awaitility:awaitility:${property("awaitilityVersion")}")
}
