plugins {
    id("idfc.library-conventions")
}

// THE HOMOGENEOUS CAPABILITY FRAMEWORK (BRD §2, §9). The shared shell every
// capability is built from: consume cap.<key>.request.v1 -> resolve operation ->
// execute -> produce cap.<key>.response.v1, idempotent on (runId,nodeId) via an
// insert-if-absent/compute-once store. Cross-cutting lives here, NOT per service.
description = "shared capability framework — homogeneous engine-invokable shell"

dependencies {
    api(project(":shared:shared-domain"))               // THE CAPABILITY CONTRACT
    // api (not implementation) so framework apps (echo/mandate/lending-servicing) get
    // platform-messaging's Kafka error-handler/DLQ auto-config on THEIR classpath.
    api(project(":platform:platform-messaging"))
    implementation("org.springframework.kafka:spring-kafka:${property("springKafkaVersion")}")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    compileOnly("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

tasks.named<Test>("test") { useJUnitPlatform() }
