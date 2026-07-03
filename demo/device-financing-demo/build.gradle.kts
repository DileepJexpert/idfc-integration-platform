plugins {
    id("idfc.spring-boot-app-conventions")
}

// DEMO SCAFFOLDING (legacy-patterns demo) — NOT production. The brand-as-config
// proof: ONE capability, per-brand behaviour entirely in config rows, vendors
// mocked in-process. The production generic-http capability is census-gated
// (docs/legacy-analysis-review.md §6/§8) and this module must never grow into it.
description = "DEMO — device-financing brand-as-config capability (vendors mocked)"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-capability"))
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
