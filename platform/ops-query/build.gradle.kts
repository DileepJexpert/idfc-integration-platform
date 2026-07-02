plugins {
    id("idfc.library-conventions")
}

// THE OPS READ WINDOW (Journey Ops View, Phase 0 B.3). A LIBRARY the engine
// deploys inside its own image ("own module, same image" — G.4): it defines its
// OWN read port (OpsRunStore) which the engine adapts to its instance store, so
// the dependency arrow is engine -> ops-query and this module can never touch
// engine internals. READ-ONLY BY CONSTRUCTION: the module contains only GET
// endpoints, its DTOs are allow-listed (no payload-shaped field exists), every
// request is token-gated + audit-logged with the acting user.
description = "ops-query — audited, read-only ops API over journey run state"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.micrometer:micrometer-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<Test>("test") { useJUnitPlatform() }
