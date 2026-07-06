plugins {
    id("idfc.spring-boot-app-conventions")
}

// device-validation capability — brand-as-config: ONE capability, per-brand
// behaviour (auth scheme, validation, pass-logic path) entirely in config rows,
// so adding a brand is adding a row, not a service. In dev the vendor is the
// compose WireMock (only its response DATA is mocked); the call itself is real
// HTTP with real per-brand auth. A generic-http capability that would subsume
// bespoke vendor clients is census-gated (docs/legacy-analysis-review.md §6/§8).
description = "device-validation — brand-as-config capability (real HTTP; vendor mocked in dev)"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-capability"))
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
