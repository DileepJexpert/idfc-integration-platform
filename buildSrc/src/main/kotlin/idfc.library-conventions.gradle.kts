// Convention for plain libraries (shared/*). No Spring Boot application, but
// dependency management is available so library code can use managed versions.
plugins {
    id("idfc.java-conventions")
    `java-library`
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        // BOM swap: on IDFC's network replace this with the internal parent —
        //   mavenBom("com.idfcfirstbank:boot-parent:1.0.15")
        // boot-parent already pins Spring Boot 3.4.5 + the idfc-* libraries.
        mavenBom("org.springframework.boot:spring-boot-dependencies:${property("springBootVersion")}")
    }
}
