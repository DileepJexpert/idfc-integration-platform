# Generic runtime image for every bootable IDFC service.
#
# We build images from the already-assembled Spring Boot fat jars rather than via
# `./gradlew bootBuildImage`: Spring Boot 3.4.x's buildpack plugin talks Docker API
# v1.24, which Docker Engine 29+ rejects ("client version 1.24 is too old"). A plain
# JRE image sidesteps that and is faster. Build context is a module's build/libs dir.
#
#   docker build -f infra/docker/Dockerfile \
#     --build-arg JAR=<name>-<version>.jar \
#     -t idfc/<name>:<version> <module>/build/libs
FROM eclipse-temurin:21-jre
WORKDIR /app
ARG JAR
COPY ${JAR} /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
