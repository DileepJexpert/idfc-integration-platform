plugins {
    id("idfc.spring-boot-app-conventions")
}

// route-config-registry (BRD §7) — PLATFORM control-plane store for API-router
// endpoint + gateway routing configs (system-of-record). REST CRUD over Aerospike
// (mocked/in-memory locally). Off the hot path; config data, not transactional.
description = "route-config-registry — API-router endpoint/gateway config registry"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
}

tasks.named<Test>("test") { useJUnitPlatform() }
