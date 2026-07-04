plugins {
    id("idfc.spring-boot-app-conventions")
}

// fusion-hcm capability — the per-record Fusion HCM update/read, invoked by the
// engine over the capability framework. Real HTTP with real timeouts and
// status→failure-class mapping; in dev the vendor is the compose WireMock (only
// its response DATA is mocked). The file-batch ingress that feeds it is a
// separate edge (edges:file-batch-edge), not part of this capability.
description = "fusion-hcm — per-record Fusion HCM update/read capability (real HTTP; vendor mocked in dev)"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-capability"))
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
