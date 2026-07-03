plugins {
    id("idfc.spring-boot-app-conventions")
}

// DEMO SCAFFOLDING (legacy-patterns demo) — NOT production. Two things live here:
//   1. the mocked fusion-hcm capability (per-record LWD update), and
//   2. the DEMO FILE EDGE: a local-folder CSV poller that starts ONE ENGINE RUN
//      PER RECORD (batch grouped by correlationId). It is explicitly NOT the
//      production SFTP edge — that, and foreach execution, are census-gated
//      (docs/legacy-analysis-review.md §6/§8).
description = "DEMO — fusion-hcm capability (mocked) + local-folder file-batch edge"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-capability"))
    implementation(project(":platform:platform-messaging")) // confirmed sends
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
