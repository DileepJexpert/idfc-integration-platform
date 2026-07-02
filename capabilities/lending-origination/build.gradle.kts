plugins {
    id("idfc.spring-boot-app-conventions")
}

// lending-origination capability — on an APPROVED application it BOOKS the loan
// in FinnOne. Hexagonal: domain framework-free, FinnOne behind an OUT port with
// a mock (local/test) adapter and a real adapter. The real FinnOne integration is
// an ORACLE STORED PROCEDURE (SP_FINNONE_SUBMISSION) over JDBC — NOT HTTP.
// Implements THE CAPABILITY CONTRACT (shared:shared-domain).
description = "lending-origination capability — FinnOne loan booking (Oracle stored proc)"

dependencies {
    implementation(project(":shared:shared-domain"))   // THE CAPABILITY CONTRACT
    implementation(project(":platform:platform-messaging"))
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Real FinnOne adapter is JDBC + CallableStatement against an Oracle stored proc.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11:23.4.0.24.05")

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
