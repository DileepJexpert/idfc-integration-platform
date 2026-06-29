plugins {
    id("idfc.spring-boot-app-conventions")
}

// customer-party capability — resolves/dedups a customer against Posidex
// (source of truth per A1). Hexagonal: domain framework-free, Posidex behind an
// OUT port with mock (local/test) + real (HTTP) adapters. Implements THE
// CAPABILITY CONTRACT (shared:shared-domain).
description = "customer-party capability — Posidex customer resolve"

dependencies {
    implementation(project(":shared:shared-domain"))   // THE CAPABILITY CONTRACT
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:kafka")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed tests (real Kafka + mock vendor)."
    group = "verification"
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.named("test"))
    val dockerHost = System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock"
    environment("DOCKER_HOST", dockerHost)
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    environment("TESTCONTAINERS_CHECKS_DISABLE", "true")
    val dockerApi = System.getenv("DOCKER_API_VERSION") ?: "1.43"
    environment("DOCKER_API_VERSION", dockerApi)
    systemProperty("api.version", dockerApi)
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
}
