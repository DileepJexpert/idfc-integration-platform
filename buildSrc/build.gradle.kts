import java.util.Properties

plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

// Pull plugin versions from the root gradle.properties so versions live in ONE
// place (the convention plugins read the runtime library versions as ordinary
// project properties of the consuming modules).
val props = Properties().apply {
    file("${rootDir}/../gradle.properties").inputStream().use { load(it) }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${props["springBootVersion"]}")
    implementation("io.spring.gradle:dependency-management-plugin:${props["springDependencyManagementVersion"]}")
}
