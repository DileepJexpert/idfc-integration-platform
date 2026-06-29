import org.gradle.api.tasks.wrapper.Wrapper

// Root build. Per-module build logic lives in the convention plugins under
// buildSrc/ (idfc.java-conventions, idfc.library-conventions,
// idfc.spring-boot-app-conventions). The root stays thin on purpose.

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
