// Convention for runnable Spring Boot apps (edges/*, capabilities/*,
// orchestration/*, some platform/*). Bundles the cross-cutting stack the punch
// list mandates: actuator, micrometer + OTel, testcontainers, container-image.
// House style applies the `application` plugin alongside Spring Boot, so each app
// module sets its own mainClass.
plugins {
    id("idfc.java-conventions")
    application
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${property("springBootVersion")}")
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
        mavenBom("io.opentelemetry:opentelemetry-bom:${property("otelVersion")}")
    }
}

dependencies {
    // Web + lifecycle + locked-down actuator (observability, not exposure)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Observability: Micrometer metrics + OTel tracing bridge
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel:${property("micrometerOtelVersion")}")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Test baseline for every app
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.awaitility:awaitility:${property("awaitilityVersion")}")
}

// Reproducible OCI image via Spring Boot's buildpacks (no Dockerfile needed).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    imageName.set("idfc/${project.name}:${project.version}")
}
