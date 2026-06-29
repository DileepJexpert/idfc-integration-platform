plugins {
    id("idfc.spring-boot-app-conventions")
}

// *** Demo — the orchestration ENGINE (no longer a stub). ***
// Hexagonal: the domain (journey model + DAG-walk logic) has NO framework
// imports; Kafka and the journey-instance store sit behind OUT ports. The engine
// DEFINES the capability contract (shared:shared-domain) every capability implements.
description = "origination-journey — Kafka-driven journey orchestration engine"

dependencies {
    // THE CAPABILITY CONTRACT lives here — engine + every capability share it.
    implementation(project(":shared:shared-domain"))

    // Messaging — Kafka is a REAL local dependency (docker-compose), not mocked.
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")

    // Durable journey-instance state (the org's only datastore); selected by config.
    implementation("com.aerospike:aerospike-client-jdk21:${property("aerospikeClientVersion")}")

    // JSON (journey config + capability payloads); jackson is managed by the BOM.
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // --- Test stack ---
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:kafka")
}

// Heavyweight Testcontainers tests (real Kafka) run in a separate task so the
// fast unit suite stays Docker-free. `check`/`build` run only `test`.
val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed tests (real Kafka)."
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
