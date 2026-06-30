plugins {
    id("idfc.spring-boot-app-conventions")
}

// mandate capability (BRD §3) — the REFERENCE build of the homogeneous template.
// Owns the e-mandate registration lifecycle (state + dedup on invoiceNo); all
// vendors (Digio/Ingenico via Kong, ENACH/NPCI, QuickPay, Dwarf, SFDC-SMS, CBS)
// behind OUT ports, MOCKED for local run. Engine-invokable via the shared
// capability framework (shared:shared-capability).
description = "mandate capability — e-mandate registration lifecycle"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-capability"))
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
