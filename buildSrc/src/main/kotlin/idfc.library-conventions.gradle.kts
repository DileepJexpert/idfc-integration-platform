// Convention for plain libraries (shared/*). No Spring Boot application, but
// dependency management is available so library code can use managed versions.
plugins {
    id("idfc.java-conventions")
    `java-library`
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${property("springBootVersion")}")
    }
}
