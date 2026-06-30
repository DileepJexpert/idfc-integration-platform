plugins {
    id("idfc.spring-boot-app-conventions")
}

// echo — the trivial reference capability that PROVES the homogeneous framework:
// it declares one Capability bean and the shared shell does everything else
// (Kafka request/response, idempotent dispatch). No per-capability plumbing.
description = "echo capability — proves the shared capability framework"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-capability"))
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
