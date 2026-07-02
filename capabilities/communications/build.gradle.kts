plugins {
    id("idfc.spring-boot-app-conventions")
}

// communications — the SENDSMS/OTP notification ACTION handler (BRD: not every
// SVCNAME is a journey; some are fire-and-forget comms). It consumes the SFDC
// edge's SENDSMS output (a canonical envelope carrying the OPAQUE Salesforce Task
// body) and sends the SMS via a mock sender. THIS capability owns the payload
// meaning (Mobile__c/Description) — the edge stayed schema-agnostic.
description = "communications capability — SENDSMS/OTP notification action (mock sender)"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":platform:platform-messaging"))
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
