plugins {
    id("idfc.spring-boot-app-conventions")
}

// imps-disbursal — the digital-lending SYNC lane's fund-transfer capability.
// A partner (INDMONEY) POSTs impsFT and BLOCKS for the result; this is NOT the
// async journey engine — no journeyInstanceId, no Kafka, no engine state. Sync
// REST ingress (fail-closed Bearer) -> in-thread invoke -> ImpsFtPort real HTTP
// with mandatory timeouts. status:S = success, non-S = business "no" (returned,
// not a 5xx), timeout/5xx = technical error (uniform 5xx). idempotentId prevents
// a double transfer. The vendor is WireMock (mock-imps) in dev — only its DATA is
// mocked; the call is real HTTP. Real backend host later = config swap.
description = "imps-disbursal — digital-lending SYNC IMPS fund transfer (real HTTP; vendor mocked in dev)"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
