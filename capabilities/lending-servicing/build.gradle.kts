plugins {
    id("idfc.spring-boot-app-conventions")
}

// lending-servicing capability (BRD §4) — gold-loan closure/foreclosure servicing
// + hosts the MSSF (Maruti) adapter. Reads FinnOne (foreclosure, READ only),
// writes SFDC cases; NEVER books. Built on the shared capability framework; all
// externals (FinnOne/SFDC/CommHub/MSSF-via-Kong) behind mocked OUT ports.
description = "lending-servicing capability — closure/foreclosure + MSSF adapter"

dependencies {
    implementation(project(":shared:shared-domain"))
    implementation(project(":shared:shared-capability"))
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }
