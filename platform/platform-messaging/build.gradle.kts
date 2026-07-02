plugins {
    id("idfc.library-conventions")
}

// SHARED KAFKA HELPERS (settings.gradle.kts: "shared Kafka helpers").
// Home of the platform's uniform Kafka reliability contract:
//   - a container CommonErrorHandler (DefaultErrorHandler) that retries with
//     bounded backoff and then dead-letters to <topic>.dlq (poison messages go
//     straight there, no retry), auto-applied to every app's Boot-autoconfigured
//     listener factory just by depending on this module;
//   - KafkaDelivery.confirm(...) so producers block on the send future with a
//     timeout instead of firing and forgetting.
// Nothing here is per-service; the failure contract lives ONCE, here.
description = "platform-messaging — shared Kafka reliability helpers (error handler + DLQ + confirmed delivery)"

dependencies {
    api("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-core")
}

tasks.named<Test>("test") { useJUnitPlatform() }
