plugins {
    id("idfc.spring-boot-app-conventions")
}

// *** Slice 1 — the one module implemented for real. ***
// Hexagonal: the domain has NO framework imports; all externals sit behind OUT
// ports with mock (local/test) and real (prod, later slice) adapters.
description = "SFDC ingress edge — thin protocol edge with idempotent dedupe (Slice 1)"

dependencies {
    // The canonical origination envelope is a SHARED platform contract (so every
    // channel edge emits the identical shape the engine consumes).
    implementation(project(":shared:shared-domain"))
    implementation(project(":platform:platform-messaging"))

    // Messaging — Kafka is a REAL local dependency (docker-compose), not mocked.
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")

    // Idempotency store — REAL local Aerospike (the only datastore the org has).
    implementation("com.aerospike:aerospike-client-jdk21:${property("aerospikeClientVersion")}")

    // Resilience4j — bounded concurrency for the FinnOne backpressure harness.
    implementation("io.github.resilience4j:resilience4j-spring-boot3:${property("resilience4jVersion")}")
    implementation("io.github.resilience4j:resilience4j-bulkhead:${property("resilience4jVersion")}")

    // JSON (canonical envelope / claim-check payloads); jackson is managed by the BOM.
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // --- Test stack ---
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:kafka")
    // No official Aerospike Testcontainers module: the concurrency test drives a
    // real Aerospike via a GenericContainer (see AerospikeTestSupport).
}

// Run the heavyweight Testcontainers tests (real Aerospike/Kafka) in a separate
// task so the fast unit suite stays Docker-free. `check` runs only `test`.
val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed tests (real Aerospike + Kafka)."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.named("test"))

    // Point Testcontainers at the daemon socket and skip Ryuk (the reaper needs
    // privileges this sandbox doesn't grant). Honour an externally-set DOCKER_HOST
    // if present. CI with a standard Docker setup needs none of this.
    val dockerHost = System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock"
    environment("DOCKER_HOST", dockerHost)
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    environment("TESTCONTAINERS_CHECKS_DISABLE", "true")
    // docker-java defaults to API 1.32; modern daemons require >= 1.40. Pin it
    // both ways: the env var for docker-java's env loader and the system property
    // its config builder reads directly.
    val dockerApi = System.getenv("DOCKER_API_VERSION") ?: "1.43"
    environment("DOCKER_API_VERSION", dockerApi)
    systemProperty("api.version", dockerApi)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// NOTE: integrationTest is intentionally NOT wired into `check`/`build` so the
// default build stays Docker-free and fast. The Aerospike concurrency gate is
// run explicitly: `./gradlew :edges:sfdc-ingress-edge:integrationTest`.
