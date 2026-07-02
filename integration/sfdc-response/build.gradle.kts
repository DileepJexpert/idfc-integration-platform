plugins {
    id("idfc.spring-boot-app-conventions")
}

// sfdc-response — the CONSOLIDATION of the ~13 per-org sfdc-<org>-response-service into
// ONE egress capability + per-org config (orgId -> response topic / SFDC object). Both
// success decisions and failure notifications route out through here (verification spec
// v2 §B). New SFDC org = a config row, not a new service.
description = "sfdc-response — consolidated per-org SFDC egress (config-as-data)"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":platform:platform-messaging"))
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
