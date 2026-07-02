plugins {
    id("idfc.spring-boot-app-conventions")
}

// verification — the new-design replacement for the old generic-wrapper-service:
// svcName -> adapter + mapper -> downstream -> {ISSUCCESS, ERROR, DATA}. The three
// wrapper flaws are fixed BY DESIGN: endpoints come from the control-plane route
// registry (not the message) + allow-list; failures DLQ (never silent-ack);
// internal-service config is config, not hardcoded. Externals are WireMock-mocked.
description = "verification capability — per-svcName adapters/mappers over a control-plane registry"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":platform:platform-messaging"))
    implementation(project(":shared:shared-capability"))   // the classified retry-policy engine (spec v2 §C)
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
