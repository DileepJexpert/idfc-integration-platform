plugins {
    id("idfc.spring-boot-app-conventions")
}

// bureau capability — fetches a canonical credit bureau report + score from CIBIL
// (the vendor). Hexagonal: domain framework-free, CIBIL behind an OUT port with
// mock (local/test) + real (HTTP) adapters. Bureau ONLY fetches data — it does
// NOT make a credit decision (that is scoring). Implements THE CAPABILITY
// CONTRACT (shared:shared-domain).
description = "bureau capability — CIBIL credit bureau fetch"

dependencies {
    implementation(project(":shared:shared-domain"))   // THE CAPABILITY CONTRACT
    implementation(project(":platform:platform-messaging"))
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
