plugins {
    id("idfc.library-conventions")
}

// imps-disbursal — the digital-lending SYNC lane's fund-transfer capability, as a
// LIBRARY the sync ingress (edges:digital-partner-edge) invokes in-thread. NOT the
// async journey engine: no journeyInstanceId, no Kafka, no engine state. It
// implements shared-sync's SyncInvocable; ImpsFtPort does real HTTP with mandatory
// timeouts. status:S = success, non-S = business "no" (returned, not a 5xx),
// timeout/5xx = technical error. idempotentId prevents a double transfer. The
// vendor is WireMock (mock-imps) in dev — only its DATA is mocked. Real backend
// host later = config swap.
description = "imps-disbursal — digital-lending SYNC IMPS fund transfer (library; real HTTP; vendor mocked in dev)"

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
