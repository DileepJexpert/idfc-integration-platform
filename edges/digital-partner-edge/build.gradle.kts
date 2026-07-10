plugins {
    id("idfc.spring-boot-app-conventions")
}

// digital-partner-edge — the digital twin of the SFDC edge. Receives partner
// (CRED/Flipkart/GROWW) loan-origination over SYNC REST, normalizes to the SAME
// shared canonical envelope, and publishes to the SAME origination topic the
// engine consumes. The engine + capabilities are UNTOUCHED — that is the thesis.
description = "digital-partner-edge — partner REST onto the SAME engine (one platform, many doors)"

dependencies {
    // The canonical envelope is the SHARED contract — same type the SFDC edge uses.
    implementation(project(":shared:shared-domain"))
    implementation(project(":platform:platform-messaging"))

    // The digital-lending SYNC lane: this edge also hosts the synchronous doors
    // (impsFT / callLmsUtilities), invoking the capabilities in-thread — NOT via
    // the engine. shared-sync is the contract; the capabilities are libraries.
    implementation(project(":shared:shared-sync"))
    implementation(project(":capabilities:imps-disbursal"))
    implementation(project(":capabilities:lms-utilities"))
    implementation(project(":capabilities:sfdc-user-management"))

    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    // SAME idempotency store as the platform (Aerospike) — partner resends dedupe too.
    implementation("com.aerospike:aerospike-client-jdk21:${property("aerospikeClientVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:kafka")
}

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
