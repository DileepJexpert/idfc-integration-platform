plugins {
    id("idfc.library-conventions")
}

// sfdc-user-management — SFDC user/role/profile (identity) operations as a LIBRARY the
// digital sync ingress invokes IN-THREAD (like imps-disbursal / lms-utilities). NOT the
// async journey engine. It implements shared-sync's SyncInvocable; svcName selects the
// operation (per-svcName route, mirroring the verification capability's ConfigRouteResolver),
// and — the one new architectural element — the request's ORG NAME selects WHICH SFDC org
// instance to call (org-as-egress-target). Endpoints come from OUR org table (a curated
// allow-list), never from the message. Reads run on the sync lane with no idempotency key;
// writes (slice 2) are idempotency-guarded. Vendors are WireMock (mock-sfdc-org-a/-b) in dev
// — only their DATA is mocked. Real SFDC orgs later = config (host+token) swaps, no code.
description = "sfdc-user-management — SYNC SFDC identity ops with ORG-targeted egress routing (library; real HTTP; orgs mocked in dev)"

dependencies {
    api(project(":shared:shared-sync"))
    implementation(project(":shared:shared-domain"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<Test>("test") { useJUnitPlatform() }
