import org.gradle.api.tasks.wrapper.Wrapper

// Root build. Per-module build logic lives in the convention plugins under
// buildSrc/ (idfc.java-conventions, idfc.library-conventions,
// idfc.spring-boot-app-conventions). SonarQube is applied here (multi-module
// aggregation); the plugin comes from the buildSrc classpath so no version is
// declared and no plugin-portal lookup is needed.
plugins {
    id("org.sonarqube")
}

sonar {
    properties {
        property("sonar.projectKey", findProperty("sonar.projectKey") ?: System.getenv("SONAR_PROJECT_KEY") ?: "")
        property("sonar.host.url", findProperty("sonar.host.url") ?: System.getenv("SONAR_URL") ?: "")
        property("sonar.login", findProperty("sonar.login") ?: System.getenv("SONAR_LOGIN") ?: "")
        property("sonar.projectName", rootProject.name)
        property("sonar.qualitygate.wait", "true")
        property(
            "sonar.coverage.exclusions",
            "**/build/**,**/generated/**,**/test/**,**/src/test/**,**/config/**,**/exception/**,**/domain/model/**"
        )
    }
}

tasks.register("printModules") {
    description = "Lists every Gradle module in the monorepo."
    group = "help"
    doLast {
        subprojects.map { it.path }.sorted().forEach { println(it) }
    }
}

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
}
