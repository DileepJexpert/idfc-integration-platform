plugins {
    id("idfc.spring-boot-app-conventions")
}

// brand-router (BRD §6) — a THIN routing ADAPTER (not a capability). Routes SFDC
// brand messages to Kafka (partitioned brands) or ActiveMQ (non-partitioned), by
// config (`brand.partitions`). On Kafka send failure -> ActiveMQ fallback. Owns
// nothing. ActiveMQ is behind a mocked port locally.
description = "brand-router — SFDC brand message routing (Kafka | ActiveMQ)"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
