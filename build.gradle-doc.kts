import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    idea
    eclipse
    jacoco
    checkstyle
    pmd

    id("org.springframework.boot") version "4.1.0"
    id("au.com.dius.pact") version "4.7.0"
    id("com.github.spotbugs") version "6.0.26"
    id("com.idfcfirstbank.codestandards") version "1.0.0"
    id("org.sonarqube") version "7.3.1.8318"
}

group = "com.idfcfirstbank"
version = "1.0.0"

description = "doc-ai-parser-service"

val idfcPlatform = "com.idfcfirstbank:boot-parent:2.0.1"

val idfcRepositoryUrl = providers.gradleProperty("idfcRepositoryUrl")
    .orElse("https://artifactory.idfcfirstbank.com:443/artifactory/api-boot-maven-local")

val idfcRepositoryUser = providers.gradleProperty("idfcRepositoryUser")
    .orElse(providers.environmentVariable("IDFC_REPOSITORY_USER"))

val idfcRepositoryPassword = providers.gradleProperty("idfcRepositoryPassword")
    .orElse(providers.environmentVariable("IDFC_REPOSITORY_PASSWORD"))

repositories {
    maven {
        name = "idfcArtifactory"
        url = uri(idfcRepositoryUrl.get())

        if (idfcRepositoryUser.isPresent && idfcRepositoryPassword.isPresent) {
            credentials {
                username = idfcRepositoryUser.get()
                password = idfcRepositoryPassword.get()
            }
        }
    }

    // Enable only when permitted by IDFC repository policy.
    // mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

springBoot {
    mainClass.set("com.idfcfirstbank.DocAiParserServiceApplication")
}

jacoco {
    toolVersion = "0.8.15"
}

checkstyle {
    toolVersion = "10.21.1"
    configFile = file("${rootProject.projectDir}/config/checkstyle/google_checks.xml")
    isIgnoreFailures = false
    isShowViolations = true
}

spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
    ignoreFailures.set(false)
}

/*
 * The service imports only the IDFC platform. The IDFC POM imports
 * Spring Boot 4.1 dependency constraints, so do not also import
 * org.springframework.boot:spring-boot-dependencies here.
 */
dependencies {
    implementation(platform(idfcPlatform))
    annotationProcessor(platform(idfcPlatform))
    testImplementation(platform(idfcPlatform))
    testAnnotationProcessor(platform(idfcPlatform))

    // SpotBugs rules.
    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
    spotbugsPlugins("com.mebigfatguy.sb-contrib:sb-contrib:7.6.4")

    // Spring Boot application stack.
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    /*
     * Keep the Servlet application model while still allowing WebClient use.
     * Do not add spring-boot-starter-webflux unless this service must run as a
     * reactive Netty server.
     */
    implementation("org.springframework:spring-webflux")

    // API documentation for Spring Boot 4.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")

    // Kafka. Versions are controlled by Spring Boot 4.1 through the IDFC BOM.
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // Aerospike.
    implementation("com.aerospike:spring-data-aerospike")
    implementation("com.idfcfirstbank:aerospike-mule-connector")

    // Relational data and database migration.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.liquibase:liquibase-core")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11")

    // Resilience4j Boot 4 integration.
    implementation("io.github.resilience4j:resilience4j-spring-boot4")
    implementation("io.github.resilience4j:resilience4j-bulkhead")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker")
    implementation("io.github.resilience4j:resilience4j-retry")

    // Metrics and tracing. Use Micrometer-to-OpenTelemetry as the application path.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.opentelemetry:opentelemetry-extension-trace-propagators")

    // IDFC internal observability/security utilities.
    implementation("com.idfcfirstbank:jmi-logging-utility")
    implementation("com.idfcfirstbank:jmi-monitoring-utility")
    implementation("com.idfcfirstbank:oauth-middleware")
    implementation("com.idfcfirstbank:middleware-tracing-utility") {
        exclude(group = "com.idfcfirstbank", module = "db-utils")
    }

    implementation("net.logstash.logback:logstash-logback-encoder")

    // Lombok.
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // Unit, slice, integration and contract tests. No Testcontainers.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.wiremock:wiremock-standalone")
    testImplementation("au.com.dius.pact.provider:junit5spring")
    testImplementation("au.com.dius.pact.consumer:junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Existing performance-test libraries kept out of the production runtime.
    testImplementation("io.gatling:gatling-core-java")
    testImplementation("io.gatling:gatling-http-java")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

/*
 * Pact tests are separated from the normal unit/integration task so the broker
 * is not required for every local test run.
 */
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("pact")
    }
}

tasks.register<Test>("pactTest") {
    group = "verification"
    description = "Runs Pact provider and consumer tests tagged with @Tag(\"pact\")."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    useJUnitPlatform {
        includeTags("pact")
    }

    shouldRunAfter(tasks.named("test"))

    providers.gradleProperty("pactBrokerUrl").orNull?.let {
        systemProperty("pactbroker.url", it)
    }
    systemProperty(
        "pact.verifier.publishResults",
        providers.gradleProperty("pactPublishResults").orElse("false").get()
    )
    systemProperty(
        "pact.provider.version",
        providers.gradleProperty("pactProviderVersion").orElse(version.toString()).get()
    )
}

/*
 * Keep production static-analysis checks active. Test-only checks are disabled
 * to match the current project behaviour and can be enabled after rule cleanup.
 */
tasks.matching {
    it.name == "pmdTest" || it.name == "spotbugsTest" || it.name == "checkstyleTest"
}.configureEach {
    enabled = false
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("doc-ai-parser-service.jar")
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestReport"))
}
