// Base Java conventions shared by EVERY module (libraries and apps).
// House style: Java 21 toolchain, jacoco coverage, maven-publish.
plugins {
    java
    jacoco
    `maven-publish`
}

group = "com.idfcfirstbank"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of((property("javaVersion") as String).toInt())
    }
}

repositories {
    mavenCentral()
    // boot-parent swap: when building on IDFC's network, add the internal Nexus here
    // (or in settings.gradle.kts dependencyResolutionManagement) so boot-parent +
    // idfc-* artifacts resolve. See the BOM note in the *-conventions plugins.
    // maven { url = uri(System.getenv("IDFC_NEXUS_URL") ?: "https://nexus.idfcfirstbank.com/repository/maven") }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        csv.required.set(false)
        html.required.set(true)
    }
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
