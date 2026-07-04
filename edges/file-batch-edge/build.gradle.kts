plugins {
    id("idfc.spring-boot-app-conventions")
}

// file-batch-edge — a LOCAL-FOLDER CSV ingress edge (thin; no business logic).
// Drops become ONE ENGINE RUN PER RECORD via the same envelope/topic the other
// edges use, so per-record status/retry/DLQ/ops ride the existing platform. It
// is explicitly NOT the production SFTP edge (that, and in-journey foreach, are
// census-gated — docs/legacy-analysis-review.md §6/§8): this is the pre-SFTP shape.
description = "file-batch-edge — local-folder CSV ingress, one engine run per record (pre-SFTP scaffold)"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":platform:platform-messaging")) // confirmed sends (KafkaDelivery)
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
